package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.KafkaTopicsProperties;
import org.example.dto.IndexMessage;
import org.example.dto.IndexMessage.ChunkItem;
import org.example.entity.IndexChunkEntity;
import org.example.entity.IndexJobEntity;
import org.example.repository.IndexChunkRepository;
import org.example.repository.IndexJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kafka 索引消息消费者 —— 包含 Parser Consumer 和 Embedding/Index Consumer 两个角色。
 * <p>
 * - Parser: 消费 paper.index.requested，解析 PDF，生成分片，发布 paper.index.embedding
 * - EmbeddingIndexer: 消费 paper.index.embedding，批量 embedding，批量写 Milvus，更新状态
 */
@Component
public class KafkaIndexConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaIndexConsumer.class);

    private final KafkaTopicsProperties topics;
    private final KafkaIndexProducer producer;
    private final IndexJobRepository jobRepo;
    private final IndexChunkRepository chunkRepo;
    private final IndexJobService jobService;
    private final EmbeddingBatcher batcher;
    private final PaperParseService paperParseService;
    private final DocumentChunkService chunkService;
    private final VectorEmbeddingService embeddingService;
    private final VectorIndexService vectorIndexService;
    private final ObjectMapper objectMapper;

    /** 任务级别的进度缓存（jobId -> 已完成分片数），减少 MySQL 写压力 */
    private final Map<String, AtomicInteger> progressCache = new ConcurrentHashMap<>();

    public KafkaIndexConsumer(KafkaTopicsProperties topics, KafkaIndexProducer producer,
                              IndexJobRepository jobRepo, IndexChunkRepository chunkRepo,
                              IndexJobService jobService, EmbeddingBatcher batcher,
                              PaperParseService paperParseService, DocumentChunkService chunkService,
                              VectorEmbeddingService embeddingService, VectorIndexService vectorIndexService,
                              ObjectMapper objectMapper) {
        this.topics = topics;
        this.producer = producer;
        this.jobRepo = jobRepo;
        this.chunkRepo = chunkRepo;
        this.jobService = jobService;
        this.batcher = batcher;
        this.paperParseService = paperParseService;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.vectorIndexService = vectorIndexService;
        this.objectMapper = objectMapper;
    }

    // ===================== Parser Consumer =====================

    /**
     * 消费 paper.index.requested，解析 PDF 全文，生成分片，写入 index_chunk，发布 embedding 消息。
     */
    @KafkaListener(
            topics = "#{kafkaTopicsProperties.getTopic('index-requested')}",
            groupId = "paper-index-parser",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeRequested(IndexMessage msg, Acknowledgment ack) {
        var jobId = msg.getJobId();
        var indexVersion = msg.getIndexVersion();
        log.info("Parser 消费请求: jobId={}", jobId);

        try {
            // 1. 更新任务状态为 PARSING
            jobService.updateStatus(jobId, IndexJobEntity.STATUS_PARSING);

            // 2. 从数据库获取任务信息，找到文件路径
            var job = jobRepo.findById(jobId);
            if (job == null) {
                log.error("任务不存在: jobId={}", jobId);
                jobService.markFailed(jobId, "任务不存在");
                ack.acknowledge();
                return;
            }
            var filePath = job.getSourcePath();
            var path = Path.of(filePath);

            // 3. 检查文件是否存在
            if (!Files.exists(path)) {
                log.error("文件不存在: {}", filePath);
                jobService.markFailed(jobId, "文件不存在: " + filePath);
                ack.acknowledge();
                return;
            }

            // 4. 解析 PDF 并分片
            var isPdf = filePath.toLowerCase().endsWith(".pdf");
            String content;
            try {
                content = isPdf ? paperParseService.extractText(filePath) : Files.readString(path);
            } catch (Exception e) {
                log.error("文件解析失败: jobId={}, path={}", jobId, filePath, e);
                jobService.markFailed(jobId, "文件解析失败: " + e.getMessage());
                ack.acknowledge();
                return;
            }

            var documentChunks = chunkService.chunkDocument(content, filePath, isPdf);
            log.info("分片完成: jobId={}, totalChunks={}", jobId, documentChunks.size());

            // 5. 生成 ChunkItem 列表并写入 index_chunk
            var chunkItems = new ArrayList<ChunkItem>();
            var chunkEntities = new ArrayList<IndexChunkEntity>();
            var now = LocalDateTime.now();

            for (int i = 0; i < documentChunks.size(); i++) {
                var dc = documentChunks.get(i);
                var text = dc.getContent();
                var hash = batcher.computeChunkHash(text);
                var metadata = buildChunkMetadata(filePath, dc, documentChunks.size());

                chunkItems.add(new ChunkItem(i, text, hash, text.length(), metadata));

                var ce = new IndexChunkEntity();
                ce.setJobId(jobId);
                ce.setIndexVersion(indexVersion);
                ce.setChunkIndex(i);
                ce.setContentHash(hash);
                ce.setContentLength(text.length());
                ce.setChunkStatus(IndexChunkEntity.STATUS_PENDING);
                ce.setCreatedAt(now);
                ce.setUpdatedAt(now);
                chunkEntities.add(ce);
            }

            // 6. 批量写入 index_chunk
            chunkRepo.batchInsert(chunkEntities);

            // 7. 更新任务的分片总数
            jobRepo.updateTotalChunks(jobId, documentChunks.size());

            // 8. 切分批次并发布 embedding 消息
            var batches = batcher.splitIntoBatches(jobId, job.getDocumentId(), indexVersion, chunkItems);
            for (var batch : batches) {
                producer.sendEmbedding(batch);
            }

            // 9. 更新任务状态为 INDEXING
            jobService.updateStatus(jobId, IndexJobEntity.STATUS_INDEXING);

            log.info("Parser 完成: jobId={}, batches={}", jobId, batches.size());
            ack.acknowledge();

        } catch (Exception e) {
            log.error("Parser 处理失败: jobId={}", jobId, e);
            jobService.markFailed(jobId, "Parser 处理异常: " + e.getMessage());
            ack.acknowledge();
        }
    }

    // ===================== Embedding/Index Consumer =====================

    /**
     * 消费 paper.index.embedding，批量 embedding + 批量写入 Milvus。
     */
    @KafkaListener(
            topics = "#{kafkaTopicsProperties.getTopic('embedding')}",
            groupId = "paper-index-embedding-indexer",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeEmbedding(IndexMessage msg, Acknowledgment ack) {
        var jobId = msg.getJobId();
        var indexVersion = msg.getIndexVersion();
        var batchId = msg.getBatchId();
        var chunks = msg.getChunks();

        if (chunks == null || chunks.isEmpty()) {
            log.warn("空 batch，跳过: batchId={}", batchId);
            ack.acknowledge();
            return;
        }

        log.info("Embedding 消费 batch: jobId={}, batchId={}, chunks={}", jobId, batchId, chunks.size());

        try {
            // 1. 过滤已经 INDEXED 的分片（幂等：跳过已成功的）
            var pendingChunks = new ArrayList<ChunkItem>();
            for (var c : chunks) {
                var status = getChunkStatus(jobId, indexVersion, c.getChunkIndex());
                if ("INDEXED".equals(status)) {
                    log.debug("分片已索引，跳过: jobId={}, chunk={}", jobId, c.getChunkIndex());
                } else {
                    pendingChunks.add(c);
                }
            }

            if (pendingChunks.isEmpty()) {
                log.info("所有分片已索引，跳过 batch: batchId={}", batchId);
                checkAndComplete(jobId, indexVersion, chunks.size(), ack);
                return;
            }

            // 2. 批量生成 embedding
            var texts = pendingChunks.stream().map(ChunkItem::getContent).toList();
            List<List<Float>> denseVectors;
            try {
                denseVectors = embeddingService.generateEmbeddings(texts);
            } catch (Exception e) {
                log.error("批量 embedding 失败: batchId={}, error={}", batchId, e.getMessage());
                // 标记所有 pending 分片为 FAILED
                for (var c : pendingChunks) {
                    chunkRepo.markFailed(jobId, indexVersion, c.getChunkIndex(), "Embedding 失败: " + e.getMessage());
                }
                checkFailed(jobId, indexVersion, chunks.size(), ack);
                return;
            }

            // 3. 批量写入 Milvus（使用 vectorIndexService 的新方法）
            var successCount = new AtomicInteger(0);
            for (int i = 0; i < pendingChunks.size(); i++) {
                var c = pendingChunks.get(i);
                var dense = denseVectors.get(i);
                try {
                    var sparse = org.example.service.SparseVectorGenerator.generate(c.getContent());
                    var milvusId = vectorIndexService.insertWithVersion(
                            c.getContent(), dense, sparse, c.getMetadata(),
                            c.getChunkIndex(), msg.getDocumentId(), indexVersion);

                    // 更新分片状态
                    chunkRepo.markIndexed(jobId, indexVersion, c.getChunkIndex(), milvusId);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("分片写入失败: jobId={}, chunk={}, error={}", jobId, c.getChunkIndex(), e.getMessage());
                    chunkRepo.markFailed(jobId, indexVersion, c.getChunkIndex(), "Milvus 写入失败: " + e.getMessage());
                }
            }

            // 4. 更新任务进度
            updateProgress(jobId, indexVersion);

            // 5. 检查任务是否完成
            checkAndComplete(jobId, indexVersion, chunks.size(), ack);

        } catch (Exception e) {
            log.error("Embedding consumer 异常: batchId={}", batchId, e);
            ack.acknowledge();
        }
    }

    // ===================== 辅助方法 =====================

    /** 获取分片状态（从 MySQL） */
    private String getChunkStatus(String jobId, String indexVersion, int chunkIndex) {
        try {
            var chunks = chunkRepo.findByJobId(jobId, indexVersion);
            return chunks.stream()
                    .filter(c -> c.getChunkIndex() == chunkIndex)
                    .findFirst()
                    .map(IndexChunkEntity::getChunkStatus)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("查询分片状态失败: jobId={}, chunk={}", jobId, chunkIndex);
            return null;
        }
    }

    /** 检查全部完成则发布 completed 消息 */
    private synchronized void checkAndComplete(String jobId, String indexVersion,
                                                int batchChunkCount, Acknowledgment ack) {
        try {
            // 获取已完成数（精确从数据库查）
            var indexed = chunkRepo.countByStatus(jobId, indexVersion, IndexChunkEntity.STATUS_INDEXED);
            var failed = chunkRepo.countByStatus(jobId, indexVersion, IndexChunkEntity.STATUS_FAILED);
            var total = indexed + failed + chunkRepo.countByStatus(jobId, indexVersion, IndexChunkEntity.STATUS_PENDING);

            // 更新进度
            jobRepo.updateProgress(jobId, indexed, IndexJobEntity.STATUS_INDEXING);

            // 从 job 获取 totalChunks
            var job = jobRepo.findById(jobId);
            if (job == null) {
                ack.acknowledge();
                return;
            }
            var totalChunks = job.getTotalChunks();

            // 如果所有 chunk 都处理完成
            if (indexed + failed >= totalChunks) {
                if (indexed == totalChunks) {
                    // 全部成功 → 版本发布
                    try {
                        vectorIndexService.publishVersion(job.getDocumentId(), indexVersion);
                    } catch (Exception e) {
                        log.warn("版本发布失败（不影响主流程）: {}", e.getMessage());
                    }
                    jobService.markSucceeded(jobId);
                    producer.sendCompleted(IndexMessage.completed(jobId, job.getDocumentId(), indexVersion));
                } else if (indexed > 0) {
                    // 部分成功部分失败
                    jobRepo.updateStatus(jobId, IndexJobEntity.STATUS_FAILED,
                            "部分分片失败: 成功 " + indexed + "/" + totalChunks);
                    producer.sendCompleted(IndexMessage.completed(jobId, job.getDocumentId(), indexVersion));
                } else {
                    // 全部失败
                    jobService.markFailed(jobId, "所有分片索引失败 (" + totalChunks + " 个)");
                }
            }

            ack.acknowledge();
        } catch (Exception e) {
            log.error("完成检查失败: jobId={}", jobId, e);
            ack.acknowledge();
        }
    }

    /** 检查是否全部失败 */
    private void checkFailed(String jobId, String indexVersion, int batchChunkCount, Acknowledgment ack) {
        try {
            var failed = chunkRepo.countByStatus(jobId, indexVersion, IndexChunkEntity.STATUS_FAILED);
            var j = jobRepo.findById(jobId);
            var total = j != null ? j.getTotalChunks() : batchChunkCount;
            if (failed >= total) {
                jobService.markFailed(jobId, "所有分片索引失败");
            }
            ack.acknowledge();
        } catch (Exception e) {
            ack.acknowledge();
        }
    }

    /** 异步更新进度（带缓存减少数据库写压力） */
    private void updateProgress(String jobId, String indexVersion) {
        var indexed = chunkRepo.countByStatus(jobId, indexVersion, IndexChunkEntity.STATUS_INDEXED);
        jobRepo.updateProgress(jobId, indexed, IndexJobEntity.STATUS_INDEXING);
    }

    /** 构建分片元数据 */
    private Map<String, Object> buildChunkMetadata(String filePath,
                                                     org.example.dto.DocumentChunk chunk, int total) {
        var path = Path.of(filePath);
        var name = path.getFileName().toString();
        var ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("_source", filePath.replace("\\", "/"));
        m.put("_extension", ext);
        m.put("_file_name", name);
        m.put("chunkIndex", chunk.getChunkIndex());
        m.put("totalChunks", total);
        if (chunk.getTitle() != null && !chunk.getTitle().isBlank()) m.put("title", chunk.getTitle());
        return m;
    }
}
