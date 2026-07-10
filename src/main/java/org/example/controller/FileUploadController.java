package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.ApiResponse;
import org.example.dto.FileUploadRes;
import org.example.dto.IndexMessage;
import org.example.service.IndexJobService;
import org.example.service.KafkaIndexProducer;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

@RestController
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    /** 允许的 MIME 类型前缀 */
    private static final Set<String> ALLOWED_MIME_PREFIXES = Set.of(
            "application/pdf", "text/plain", "text/markdown"
    );

    /** 路径遍历防护：不允许的路径字符 */
    private static final Set<Character> DANGEROUS_CHARS = Set.of(
            '/', '\\', ':', '*', '?', '"', '<', '>', '|'
    );

    /** 并发上传限流：同时最多 3 个上传任务 */
    private final Semaphore uploadPermits = new Semaphore(3);

    @Autowired private FileUploadConfig uploadConfig;
    @Autowired private VectorIndexService indexService;

    /** 索引模式：sync（同步，兼容旧模式）/ async（异步，Kafka 模式） */
    @Value("${indexing.mode:sync}")
    private String indexingMode;

    @Autowired(required = false) private IndexJobService jobService;
    @Autowired(required = false) private KafkaIndexProducer producer;

    @PostMapping("/api/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        // 文件非空校验
        if (file.isEmpty()) return ResponseEntity.badRequest().body(ApiResponse.fail("文件不能为空"));

        var filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) return ResponseEntity.badRequest().body(ApiResponse.fail("文件名不能为空"));

        // 路径穿越防护：校验文件名中不含危险字符
        var safeName = sanitizeFilename(filename);
        if (safeName == null) {
            log.warn("文件名包含非法字符，已拒绝: {}", filename);
            return ResponseEntity.badRequest().body(ApiResponse.fail("文件名包含非法字符"));
        }

        // 扩展名校验
        var ext = extOf(safeName);
        if (!isAllowed(ext)) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("不支持的文件格式: " + ext));
        }

        // MIME 类型校验
        var contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            var mimePrefix = contentType.contains(";") ? contentType.substring(0, contentType.indexOf(';')).trim() : contentType.trim();
            boolean mimeOk = ALLOWED_MIME_PREFIXES.stream().anyMatch(mimePrefix::startsWith);
            if (!mimeOk) {
                log.warn("MIME 类型不合法: {} (文件: {})", contentType, safeName);
                return ResponseEntity.badRequest().body(ApiResponse.fail("文件类型不合法"));
            }
        }

        // 并发上传限流
        if (!uploadPermits.tryAcquire()) {
            return ResponseEntity.status(429).body(ApiResponse.fail("服务器繁忙，请稍后重试"));
        }
        try {
            var dir = Path.of(uploadConfig.getPath());
            Files.createDirectories(dir);
            var target = dir.resolve(safeName);

            // 覆盖已有文件
            if (Files.exists(target)) Files.delete(target);
            Files.copy(file.getInputStream(), target);

            log.info("文件保存成功: {}", target);

            if ("async".equalsIgnoreCase(indexingMode) && jobService != null && producer != null) {
                // ===== 异步模式：发布 Kafka 消息后立即返回 =====
                var job = jobService.createJob(safeName, target);
                var msg = IndexMessage.requested(job.getJobId(), safeName, job.getIndexVersion());
                producer.sendRequested(msg);

                var resp = new FileUploadRes(safeName, target.toString(), file.getSize());
                resp.setJobId(job.getJobId());
                resp.setStatus("PENDING");
                return ResponseEntity.accepted().body(ApiResponse.ok(resp));
            } else {
                // ===== 同步模式（兼容旧流程） =====
                var paperTitle = ext.equals("pdf") ? safeName.replaceAll("(?i)\\.pdf$", "") : null;

                try {
                    indexService.indexSingleFile(target.toString());
                } catch (Exception e) {
                    log.error("向量索引失败: {}", target, e);
                }

                var resp = new FileUploadRes(safeName, target.toString(), file.getSize());
                resp.setPaperTitle(paperTitle);
                return ResponseEntity.ok(ApiResponse.ok(resp));
            }
        } catch (Exception e) {
            log.error("上传失败", e);
            return ResponseEntity.internalServerError().body(ApiResponse.fail(e.getMessage()));
        } finally {
            uploadPermits.release();
        }
    }

    // ===================== 安全校验 =====================

    /** 文件名 sanitize：移除路径遍历和危险字符 */
    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return null;
        var base = name;
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSlash >= 0) base = name.substring(lastSlash + 1);
        for (int i = 0; i < base.length(); i++) {
            if (DANGEROUS_CHARS.contains(base.charAt(i))) return null;
        }
        return base.trim();
    }

    private boolean isAllowed(String ext) {
        var allowed = uploadConfig.getAllowedExtensions();
        return allowed != null && List.of(allowed.split(",")).contains(ext);
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i == -1 ? "" : name.substring(i + 1).toLowerCase();
    }
}
