package org.example.controller;

import org.example.config.FileUploadConfig;
import org.example.dto.ApiResponse;
import org.example.dto.FileUploadRes;
import org.example.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RestController
public class FileUploadController {

    private static final Logger log = LoggerFactory.getLogger(FileUploadController.class);

    @Autowired private FileUploadConfig uploadConfig;
    @Autowired private VectorIndexService indexService;

    @PostMapping("/api/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.badRequest().body("文件不能为空");

        var filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) return ResponseEntity.badRequest().body("文件名不能为空");

        var ext = extOf(filename);
        if (!isAllowed(ext)) return ResponseEntity.badRequest().body("不支持的文件格式: " + ext);

        try {
            var dir = Path.of(uploadConfig.getPath());
            Files.createDirectories(dir);
            var target = dir.resolve(filename);

            // 覆盖已有文件
            if (Files.exists(target)) Files.delete(target);
            Files.copy(file.getInputStream(), target);

            log.info("文件保存成功: {}", target);
            var paperTitle = filename.toLowerCase().endsWith(".pdf") ? filename.replaceAll("(?i)\\.pdf$", "") : null;

            try {
                indexService.indexSingleFile(target.toString());
            } catch (Exception e) {
                log.error("向量索引失败: {}", target, e);
            }

            var resp = new FileUploadRes(filename, target.toString(), file.getSize());
            resp.setPaperTitle(paperTitle);
            return ResponseEntity.ok(ApiResponse.ok(resp));
        } catch (Exception e) {
            log.error("上传失败", e);
            return ResponseEntity.internalServerError().body(ApiResponse.fail(e.getMessage()));
        }
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
