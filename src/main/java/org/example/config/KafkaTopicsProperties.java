package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Kafka 主题配置 —— 用于 topic 名称注入，不硬编码在业务逻辑中。
 */
@Component
@ConfigurationProperties(prefix = "messaging.kafka")
public class KafkaTopicsProperties {

    /** Kafka broker 地址 */
    private String bootstrapServers;

    /** Topic 名称映射 */
    private Map<String, String> topics;

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

    public Map<String, String> getTopics() { return topics; }
    public void setTopics(Map<String, String> topics) { this.topics = topics; }

    /** 获取指定用途的 topic 名称 */
    public String getTopic(String key) {
        return topics != null ? topics.getOrDefault(key, "paper.index." + key) : "paper.index." + key;
    }
}
