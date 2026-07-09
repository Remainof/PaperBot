package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 对话服务
 * 封装 RagService，供 ChatController 调用
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private RagService ragService;

    public String executeRag(String question, List<Map<String, String>> history) {
        log.info("RAG 问答: {}", question);
        return ragService.query(question, history);
    }

    public void executeRagStream(String question, List<Map<String, String>> history, RagService.StreamCallback callback) {
        log.info("RAG 流式问答: {}", question);
        ragService.queryStream(question, history, callback);
    }
}
