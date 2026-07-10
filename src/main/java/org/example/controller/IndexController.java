package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.entity.IndexJobEntity;
import org.example.repository.IndexJobRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 索引任务查询接口。
 * <p>
 * 在异步索引模式下，用户上传文件后通过此接口轮询任务进度。
 */
@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final IndexJobRepository jobRepo;

    public IndexController(IndexJobRepository jobRepo) {
        this.jobRepo = jobRepo;
    }

    /**
     * 查询任务进度。
     * <p>
     * GET /api/index/jobs/{jobId}
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getJob(@PathVariable String jobId) {
        var job = jobRepo.findById(jobId);
        if (job == null) {
            return ResponseEntity.ok(ApiResponse.fail("任务不存在"));
        }
        var data = Map.<String, Object>of(
                "jobId", job.getJobId(),
                "documentId", job.getDocumentId(),
                "sourcePath", job.getSourcePath(),
                "status", job.getStatus(),
                "totalChunks", job.getTotalChunks(),
                "completedChunks", job.getCompletedChunks(),
                "errorMessage", job.getErrorMessage() != null ? job.getErrorMessage() : "",
                "createdAt", job.getCreatedAt() != null ? job.getCreatedAt().toString() : "",
                "updatedAt", job.getUpdatedAt() != null ? job.getUpdatedAt().toString() : ""
        );
        return ResponseEntity.ok(ApiResponse.ok(data));
    }
}
