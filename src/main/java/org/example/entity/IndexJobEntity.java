package org.example.entity;

import java.time.LocalDateTime;

/**
 * 索引任务实体 —— 对应 index_job 表。
 */
public class IndexJobEntity {

    private String jobId;
    private String documentId;
    private String sourcePath;
    private String fileHash;
    private String indexVersion;
    private String status;
    private Integer totalChunks;
    private Integer completedChunks;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== 状态常量 =====
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PARSING = "PARSING";
    public static final String STATUS_INDEXING = "INDEXING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    // ===== Getters & Setters =====
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }

    public String getIndexVersion() { return indexVersion; }
    public void setIndexVersion(String indexVersion) { this.indexVersion = indexVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }

    public Integer getCompletedChunks() { return completedChunks; }
    public void setCompletedChunks(Integer completedChunks) { this.completedChunks = completedChunks; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "IndexJobEntity{" +
                "jobId='" + jobId + '\'' +
                ", documentId='" + documentId + '\'' +
                ", status='" + status + '\'' +
                ", totalChunks=" + totalChunks +
                ", completedChunks=" + completedChunks +
                '}';
    }
}
