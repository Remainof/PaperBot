package org.example.service;

import org.example.entity.IndexJobEntity;
import org.example.repository.IndexJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 索引任务服务 —— 任务创建、状态管理、indexVersion 生成。
 */
@Service
public class IndexJobService {

    private static final Logger log = LoggerFactory.getLogger(IndexJobService.class);

    private final IndexJobRepository jobRepo;

    public IndexJobService(IndexJobRepository jobRepo) {
        this.jobRepo = jobRepo;
    }

    /**
     * 创建索引任务。
     *
     * @param documentId 文档标识（文件名）
     * @param sourcePath 文件存储路径
     * @return 已持久化的任务实体（status = PENDING）
     */
    public IndexJobEntity createJob(String documentId, Path sourcePath) {
        var job = new IndexJobEntity();
        job.setJobId(UUID.randomUUID().toString());
        job.setDocumentId(documentId);
        job.setSourcePath(sourcePath.toString().replace("\\", "/"));
        job.setFileHash(computeFileHash(sourcePath));
        job.setIndexVersion(generateIndexVersion());
        job.setStatus(IndexJobEntity.STATUS_PENDING);
        job.setTotalChunks(0);
        job.setCompletedChunks(0);
        var now = LocalDateTime.now();
        job.setCreatedAt(now);
        job.setUpdatedAt(now);

        jobRepo.insert(job);
        log.info("索引任务已创建: jobId={}, documentId={}, fileHash={}, version={}",
                job.getJobId(), documentId, job.getFileHash(), job.getIndexVersion());
        return job;
    }

    /**
     * 生成 indexVersion：时间戳 + UUID 前缀。
     * 格式：v_{timestamp_ms}_{short_uuid}
     */
    public String generateIndexVersion() {
        return "v_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 计算文件的 SHA-256 哈希。
     */
    public String computeFileHash(Path path) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = Files.readAllBytes(path);
            digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn("计算文件哈希失败，使用随机值代替: {}", e.getMessage());
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    /**
     * 更新任务状态。
     */
    public void updateStatus(String jobId, String status) {
        jobRepo.updateStatus(jobId, status, null);
        log.info("任务状态更新: jobId={}, status={}", jobId, status);
    }

    /**
     * 更新任务为失败。
     */
    public void markFailed(String jobId, String errorMessage) {
        jobRepo.updateStatus(jobId, IndexJobEntity.STATUS_FAILED, errorMessage);
        log.error("任务失败: jobId={}, error={}", jobId, errorMessage);
    }

    /**
     * 更新任务为成功。
     */
    public void markSucceeded(String jobId) {
        jobRepo.updateStatus(jobId, IndexJobEntity.STATUS_SUCCEEDED, null);
        log.info("任务完成: jobId={}", jobId);
    }

    /**
     * 检查同一文档（按 file hash）是否已有成功索引的任务。
     * 用于判重：相同内容的文件跳过重复索引。
     */
    public boolean alreadyIndexed(String documentId, Path sourcePath) {
        var hash = computeFileHash(sourcePath);
        return jobRepo.existsSucceeded(documentId, hash);
    }
}
