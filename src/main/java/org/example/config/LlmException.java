package org.example.config;

/** LLM 调用失败的专用异常 */
public class LlmException extends RuntimeException {
    public LlmException(String msg) { super(msg); }
    public LlmException(String msg, Throwable cause) { super(msg, cause); }
}
