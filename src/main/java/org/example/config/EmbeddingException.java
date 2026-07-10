package org.example.config;

/** Embedding 失败的专用异常 */
public class EmbeddingException extends RuntimeException {
    public EmbeddingException(String msg) { super(msg); }
    public EmbeddingException(String msg, Throwable cause) { super(msg, cause); }
}
