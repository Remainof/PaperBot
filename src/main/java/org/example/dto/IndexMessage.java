package org.example.dto;

import java.util.List;
import java.util.Map;

/**
 * Kafka 消息实体 —— paper.index.requested / paper.index.embedding / paper.index.completed 共用。
 * <p>
 * 消息携带待处理的 chunk 内容和元数据，不传递完整 PDF。
 */
public class IndexMessage {

    /** 消息 schema 版本 */
    private int schemaVersion;

    /** 任务ID */
    private String jobId;

    /** 文档标识 */
    private String documentId;

    /** 索引版本号 */
    private String indexVersion;

    /** 批次ID（仅 embedding 消息使用） */
    private String batchId;

    /** 分片列表 */
    private List<ChunkItem> chunks;

    /** 重试次数 */
    private int attempt;

    /** 消息创建时间 */
    private String createdAt;

    // ===== 消息类型 =====

    /** 新建索引任务 */
    public static final String TYPE_REQUESTED = "paper.index.requested";
    /** 待向量化的分片批次 */
    public static final String TYPE_EMBEDDING = "paper.index.embedding";
    /** 任务完成事件 */
    public static final String TYPE_COMPLETED = "paper.index.completed";

    public IndexMessage() {}

    /** 创建 requested 消息（仅携带文件路径，不携带分片） */
    public static IndexMessage requested(String jobId, String documentId, String indexVersion) {
        var msg = new IndexMessage();
        msg.schemaVersion = 1;
        msg.jobId = jobId;
        msg.documentId = documentId;
        msg.indexVersion = indexVersion;
        msg.attempt = 1;
        msg.createdAt = java.time.Instant.now().toString();
        return msg;
    }

    /** 创建 embedding 消息（携带分片内容） */
    public static IndexMessage embedding(String jobId, String documentId, String indexVersion,
                                          String batchId, List<ChunkItem> chunks, int attempt) {
        var msg = new IndexMessage();
        msg.schemaVersion = 1;
        msg.jobId = jobId;
        msg.documentId = documentId;
        msg.indexVersion = indexVersion;
        msg.batchId = batchId;
        msg.chunks = chunks;
        msg.attempt = attempt;
        msg.createdAt = java.time.Instant.now().toString();
        return msg;
    }

    /** 创建 completed 消息 */
    public static IndexMessage completed(String jobId, String documentId, String indexVersion) {
        var msg = new IndexMessage();
        msg.schemaVersion = 1;
        msg.jobId = jobId;
        msg.documentId = documentId;
        msg.indexVersion = indexVersion;
        msg.createdAt = java.time.Instant.now().toString();
        return msg;
    }

    // ===== Getters & Setters =====

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getIndexVersion() { return indexVersion; }
    public void setIndexVersion(String indexVersion) { this.indexVersion = indexVersion; }

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }

    public List<ChunkItem> getChunks() { return chunks; }
    public void setChunks(List<ChunkItem> chunks) { this.chunks = chunks; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    // ===== 分片项 =====

    public static class ChunkItem {
        private int chunkIndex;
        private String content;
        private String contentHash;
        private int contentLength;
        private Map<String, Object> metadata;

        public ChunkItem() {}

        public ChunkItem(int chunkIndex, String content, String contentHash,
                         int contentLength, Map<String, Object> metadata) {
            this.chunkIndex = chunkIndex;
            this.content = content;
            this.contentHash = contentHash;
            this.contentLength = contentLength;
            this.metadata = metadata;
        }

        public int getChunkIndex() { return chunkIndex; }
        public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        public String getContentHash() { return contentHash; }
        public void setContentHash(String contentHash) { this.contentHash = contentHash; }

        public int getContentLength() { return contentLength; }
        public void setContentLength(int contentLength) { this.contentLength = contentLength; }

        public Map<String, Object> getMetadata() { return metadata; }
        public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    }
}
