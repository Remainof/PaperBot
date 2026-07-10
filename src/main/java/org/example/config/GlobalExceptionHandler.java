package org.example.config;

import com.alibaba.dashscope.exception.ApiException;
import org.example.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.IOException;

/**
 * 全局异常处理器
 * 统一拦截各层异常，返回友好的 ApiResponse，避免 500 页面或堆栈泄漏。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** DashScope API 调用失败 */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleDashScopeApi(ApiException e) {
        log.error("DashScope API 调用异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail("AI 服务暂时不可用，请稍后重试"));
    }

    /** Embedding 失败 */
    @ExceptionHandler(EmbeddingException.class)
    public ResponseEntity<ApiResponse<Void>> handleEmbedding(EmbeddingException e) {
        log.error("Embedding 失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail("文本向量化服务暂时不可用"));
    }

    /** LLM 调用失败特有的异常 */
    @ExceptionHandler(LlmException.class)
    public ResponseEntity<ApiResponse<Void>> handleLlm(LlmException e) {
        log.error("LLM 调用失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail("AI 回答服务暂时不可用，请稍后重试"));
    }

    /** 文件上传超过大小限制 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传文件超限: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail("文件大小超过限制（最大 50MB）"));
    }

    /** 文件上传参数缺失 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail("缺少必要参数: " + e.getParameterName()));
    }

    /** 非法参数（文件不存在、格式不支持等） */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(e.getMessage()));
    }

    /** PDF 解析失败 */
    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiResponse<Void>> handleIo(IOException e) {
        log.error("IO 异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("文件处理失败: " + e.getMessage()));
    }

    /** 兜底：未预期的异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnhandled(Exception e) {
        log.error("未捕获异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("服务器内部错误，请联系管理员"));
    }
}
