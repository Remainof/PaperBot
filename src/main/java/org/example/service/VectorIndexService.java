package org.example.service;

import com.google.gson.Gson;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import org.example.client.LightRagClient;
import org.example.constant.MilvusConstants;
import org.example.dto.DocumentChunk;
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
        var content = isPdf ? paperParseService.extractText(filePath) : Files.readString(path);
        var meta = isPdf ? paperParseService.extractMetadata(filePath, content) : null;

        deleteExisting(path.toString());

        var chunks = chunkService.chunkDocument(content, path.toString(), isPdf);
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            var text = c.getContent();
            var dense = embeddingService.generateEmbedding(text);
            var sparse = SparseVectorGenerator.generate(text);
            var md = buildMetadata(path.toString(), c, chunks.size());
            if (isPdf && meta != null) enrichPaperMeta(md, meta);
            insert(text, dense, sparse, md, c.getChunkIndex());
            if ((i + 1) % 10 == 0 || i == chunks.size() - 1) log.info("✓ 分片 {}/{}", i + 1, chunks.size());
        }
        // 同步到 LightRAG 建图（如果启用）
        syncToLightRag(content, filePath);

        log.info("索引完成: {} ({} 分片)", filePath, chunks.size());
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
        client.insert(InsertReq.builder()
                .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .data(Collections.singletonList(json))
                .build());
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
