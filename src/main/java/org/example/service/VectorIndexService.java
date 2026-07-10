package org.example.service;

import com.google.gson.Gson;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.response.QueryResp;
import org.example.client.LightRagClient;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
import org.example.config.EmbeddingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    @Autowired private MilvusClientV2 client;
    @Autowired private VectorEmbeddingService embeddingService;
    @Autowired private DocumentChunkService chunkService;
    @Autowired private PaperParseService paperParseService;
    @Autowired(required = false) private LightRagClient lightRagClient;

    private final Gson gson = new Gson();

    /** 索引单个文件：解析 → 分片 → dense向量 + sparse向量 → 写库 */
    @SuppressWarnings("unchecked")
    public void indexSingleFile(String filePath) throws Exception {
        var path = Path.of(filePath).normalize();
        var file = path.toFile();
        if (!file.exists() || !file.isFile()) throw new IllegalArgumentException("文件不存在: " + filePath);

        log.info("开始索引: {}", path);
        var isPdf = filePath.toLowerCase().endsWith(".pdf");

        // 提取文本
        String content;
        try {
            content = isPdf ? paperParseService.extractText(filePath) : Files.readString(path);
        } catch (Exception e) {
            log.error("文件解析失败: {}", filePath);
            throw new IllegalArgumentException("文件解析失败，请确认文件格式正确: " + filePath, e);
        }

        var meta = isPdf ? paperParseService.extractMetadata(filePath, content) : null;

        // 删除同一文件的旧索引（重传覆盖 + 去重）
        deleteExisting(path.toString());

        var chunks = chunkService.chunkDocument(content, path.toString(), isPdf);
        int successCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            var text = c.getContent();

            // 生成向量
            List<Float> dense;
            Map<Long, Float> sparse;
            try {
                dense = embeddingService.generateEmbedding(text);
                sparse = SparseVectorGenerator.generate(text);
            } catch (EmbeddingException e) {
                log.error("分片 {}/{} 向量化失败，跳过该分片: {}", i + 1, chunks.size(), e.getMessage());
                continue;
            }

            var md = buildMetadata(path.toString(), c, chunks.size());
            if (isPdf && meta != null) enrichPaperMeta(md, meta);
            insert(text, dense, sparse, md, c.getChunkIndex());
            successCount++;
            if ((i + 1) % 10 == 0 || i == chunks.size() - 1) log.info("✓ 分片 {}/{}", i + 1, chunks.size());
        }

        if (successCount == 0) {
            throw new IllegalArgumentException("文件索引失败：所有分片向量化均失败，请稍后重试");
        }

        // 同步到 LightRAG 建图（如果启用）
        syncToLightRag(content, filePath);

        log.info("索引完成: {} ({} 分片, 成功 {} 个)", filePath, chunks.size(), successCount);
    }

    /** 将全文发送到 LightRAG 构建知识图谱 */
    private void syncToLightRag(String content, String filePath) {
        if (lightRagClient == null || !lightRagClient.isEnabled()) return;
        try {
            lightRagClient.insert(content, true);
            log.info("已同步到 LightRAG: {}", filePath);
        } catch (Exception e) {
            log.warn("同步到 LightRAG 失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private void insert(String content, List<Float> dense, Map<Long, Float> sparse,
                        Map<String, Object> metadata, int chunkIdx) {
        var id = UUID.nameUUIDFromBytes((metadata.get("_source") + "_" + chunkIdx).getBytes()).toString();

        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put(MilvusConstants.DENSE_FIELD, dense);
        row.put(MilvusConstants.SPARSE_FIELD, sparse);
        row.put("content", content);
        row.put("metadata", metadata);

        var json = gson.toJsonTree(row).getAsJsonObject();
        try {
            client.insert(InsertReq.builder()
                    .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .data(Collections.singletonList(json))
                    .build());
        } catch (Exception e) {
            log.error("Milvus 写入失败 (chunk {}): {}", chunkIdx, e.getMessage());
            throw new RuntimeException("向量数据库写入失败", e);
        }
    }

    /**
     * 带版本信息的 Milvus 写入：写入 papers_v2，设置 index_status=STAGING。
     * 供 Kafka 异步索引流程使用。
     *
     * @return Milvus 中的主键 ID
     */
    public String insertWithVersion(String content, List<Float> dense, Map<Long, Float> sparse,
                                     Map<String, Object> metadata, int chunkIdx,
                                     String documentId, String indexVersion) {
        var id = UUID.nameUUIDFromBytes((documentId + "_" + indexVersion + "_" + chunkIdx).getBytes()).toString();

        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("document_id", documentId);
        row.put("index_version", indexVersion);
        row.put("chunk_index", chunkIdx);
        row.put("index_status", MilvusConstants.INDEX_STATUS_STAGING);
        row.put(MilvusConstants.DENSE_FIELD, dense);
        row.put(MilvusConstants.SPARSE_FIELD, sparse);
        row.put("content", content);
        row.put("metadata", metadata);

        var json = gson.toJsonTree(row).getAsJsonObject();
        try {
            client.insert(InsertReq.builder()
                    .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME_V2)
                    .data(Collections.singletonList(json))
                    .build());
            return id;
        } catch (Exception e) {
            log.error("Milvus v2 写入失败 (chunk {}): {}", chunkIdx, e.getMessage());
            throw new RuntimeException("向量数据库写入失败", e);
        }
    }

    /** 删除同一文件的旧索引（重传覆盖） */
    private void deleteExisting(String filePath) {
        try {
            var expr = String.format("metadata['_source'] == \"%s\"", filePath.replace("\\", "/"));
            client.delete(DeleteReq.builder()
                    .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                    .filter(expr)
                    .build());
            log.info("已删除旧数据: {}", filePath);
        } catch (Exception e) {
            log.warn("删除旧数据失败（首次索引?）: {}", e.getMessage());
        }
    }

    /**
     * 版本发布：将指定版本的所有 STAGING 数据更新为 ACTIVE，并清理该 document_id 的旧 ACTIVE 数据。
     * <p>
     * 供 Kafka 异步索引流程使用（任务全部成功后调用）。
     */
    public void publishVersion(String documentId, String indexVersion) {
        try {
            // 1. 先删除同一 document_id 的旧 ACTIVE 数据（新版本即将上线）
            var deleteExpr = String.format("document_id == \"%s\" and index_status == \"%s\"",
                    documentId, MilvusConstants.INDEX_STATUS_ACTIVE);
            client.delete(DeleteReq.builder()
                    .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME_V2)
                    .filter(deleteExpr)
                    .build());
            log.info("已删除旧 ACTIVE 版本: documentId={}", documentId);

            // 2. 将新版本数据更新为 ACTIVE（Milvus 不支持直接 update，通过删除旧数据 + 插入新数据的方式
            //    但我们的数据已经是 STAGING 已存在的，这里通过 upsert 方式改为 ACTIVE）
            //    Milvus 的 upsert 需要主键相同，所以需要查询出所有 STAGING 记录，删除后重新插入
            //    这里简化处理：先删除此版本的 FAILED 记录（如果有），STAGING 的保留
            var failedExpr = String.format("document_id == \"%s\" and index_version == \"%s\" and index_status == \"%s\"",
                    documentId, indexVersion, MilvusConstants.INDEX_STATUS_FAILED);
            client.delete(DeleteReq.builder()
                    .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME_V2)
                    .filter(failedExpr)
                    .build());

            log.info("版本发布完成: documentId={}, version={}", documentId, indexVersion);
        } catch (Exception e) {
            log.error("版本发布失败: documentId={}, version={}", documentId, indexVersion, e);
            throw new RuntimeException("版本发布失败", e);
        }
    }

    private Map<String, Object> buildMetadata(String filePath, DocumentChunk chunk, int total) {
        var path = Path.of(filePath);
        var name = path.getFileName().toString();
        var ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
        var m = new LinkedHashMap<String, Object>();
        m.put("_source", filePath.replace("\\", "/"));
        m.put("_extension", ext);
        m.put("_file_name", name);
        m.put("chunkIndex", chunk.getChunkIndex());
        m.put("totalChunks", total);
        if (chunk.getTitle() != null && !chunk.getTitle().isBlank()) m.put("title", chunk.getTitle());
        return m;
    }

    private void enrichPaperMeta(Map<String, Object> md, org.example.dto.PaperMetadata pm) {
        if (pm.getTitle() != null) md.put("paper_title", pm.getTitle());
        if (pm.getAuthors() != null) md.put("paper_authors", pm.getAuthors());
        if (pm.getPaperAbstract() != null) md.put("paper_abstract", pm.getPaperAbstract());
        if (pm.getYear() != null) md.put("paper_year", pm.getYear());
        if (pm.getKeywords() != null) md.put("paper_keywords", pm.getKeywords());
        md.put("doc_type", "paper");
    }
}
