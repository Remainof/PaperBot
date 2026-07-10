package org.example.entity;

import java.time.LocalDateTime;

/**
 * 分片状态实体 —— 对应 index_chunk 表。
 */
public class IndexChunkEntity {

    private String jobId;
    private String indexVersion;
    private Integer chunkIndex;
    private String contentHash;
    private Integer contentLength;
    private String chunkStatus;
    private String milvusId;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 状态常量 =====
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_INDEXED = "INDEXED";
    public static final String STATUS_FAILED = "FAILED";

    // ===== Getters & Setters =====
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getIndexVersion() { return indexVersion; }
    public void setIndexVersion(String indexVersion) { this.indexVersion = indexVersion; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public Integer getContentLength() { return contentLength; }
    public void setContentLength(Integer contentLength) { this.contentLength = contentLength; }

    public String getChunkStatus() { return chunkStatus; }
    public void setChunkStatus(String chunkStatus) { this.chunkStatus = chunkStatus; }

    public String getMilvusId() { return milvusId; }
    public void setMilvusId(String milvusId) { this.milvusId = milvusId; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "IndexChunkEntity{" +
                "jobId='" + jobId + '\'' +
                ", chunkIndex=" + chunkIndex +
                ", chunkStatus='" + chunkStatus + '\'' +
                ", retryCount=" + retryCount +
                '}';
    }
}
