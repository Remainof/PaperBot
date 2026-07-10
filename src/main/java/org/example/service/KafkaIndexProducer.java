package org.example.service;

import org.example.config.KafkaTopicsProperties;
import org.example.dto.IndexMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Kafka 索引消息生产者 —— 发送索引任务、embedding、完成事件和 DLQ 消息。
 */
@Component
public class KafkaIndexProducer {

    private static final Logger log = LoggerFactory.getLogger(KafkaIndexProducer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicsProperties topics;

    public KafkaIndexProducer(KafkaTemplate<String, Object> kafkaTemplate,
                              KafkaTopicsProperties topics) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
    }

    /**
     * 发送 paper.index.requested 消息。
     * 消息 key = documentId，保证同一文档有序。
     */
    public void sendRequested(IndexMessage msg) {
        var topic = topics.getTopic("index-requested");
        send(topic, msg.getDocumentId(), msg, "requested");
    }

    /**
     * 发送 paper.index.embedding 消息。
     * 消息 key = batchId，保证同一 batch 有序。
     */
    public void sendEmbedding(IndexMessage msg) {
        var topic = topics.getTopic("embedding");
        send(topic, msg.getBatchId(), msg, "embedding");
    }

    /**
     * 发送 paper.index.completed 消息。
     */
    public void sendCompleted(IndexMessage msg) {
        var topic = topics.getTopic("completed");
        send(topic, msg.getJobId(), msg, "completed");
    }

    /**
     * 发送 DLQ 消息（失败消息重新投递到死信队列）。
     */
    public void sendDlq(String jobId, String documentId, Object payload, String reason) {
        var topic = topics.getTopic("dlq");
        log.warn("投递 DLQ: jobId={}, reason={}", jobId, reason);
        try {
            kafkaTemplate.send(topic, jobId, payload);
        } catch (Exception e) {
            log.error("DLQ 投递失败: jobId={}", jobId, e);
        }
    }

    private void send(String topic, String key, Object payload, String type) {
        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, payload);
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                if (log.isDebugEnabled()) {
                    log.debug("Kafka 消息发送成功: topic={}, key={}, offset={}",
                            topic, key, result.getRecordMetadata().offset());
                }
            } else {
                log.error("Kafka 消息发送失败: topic={}, key={}, type={}", topic, key, type, ex);
            }
        });
    }
}
