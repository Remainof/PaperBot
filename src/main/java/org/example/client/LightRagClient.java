package org.example.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * LightRAG HTTP 客户端
 *
 * 与 lightrag_service/server.py 通信，提供文档建图和检索问答能力。
 * 通过 lightrag.enabled 控制开关，关闭时所有方法为空操作，
 * PaperBot 完全退化为普通 Milvus RAG 模式。
 */
@Service
public class LightRagClient {

    private static final Logger log = LoggerFactory.getLogger(LightRagClient.class);

    @Value("${lightrag.enabled:false}")
    private boolean enabled;

    @Value("${lightrag.url:http://localhost:8021}")
    private String url;

    @Value("${lightrag.mode:hybrid}")
    private String mode;

    private final RestTemplate rest = new RestTemplate();

    /** 是否启用了 LightRAG */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 将文档文本发送给 LightRAG 建图。
     * LightRAG 会自动从文本中提取实体和关系，构建知识图谱。
     *
     * @param text   文档全文
     * @param chunk  是否在服务端分块后建图
     */
    public void insert(String text, boolean chunk) {
        if (!enabled) return;
        try {
            var body = Map.of("text", text, "chunk", chunk);
            rest.postForEntity(url + "/insert", body, String.class);
            log.info("LightRAG 插入了 {} 字符", text.length());
        } catch (Exception e) {
            log.warn("LightRAG 插入失败: {}", e.getMessage());
        }
    }

    /**
     * 向 LightRAG 发起 Graph RAG 检索问答
     *
     * @param question 用户问题
     * @return LLM 生成的回答
     */
    public String query(String question) {
        return query(question, mode);
    }

    /**
     * 向 LightRAG 发起 Graph RAG 检索问答（指定模式）
     *
     * @param question 用户问题
     * @param queryMode 检索模式: local / global / hybrid
     * @return LLM 生成的回答
     */
    public String query(String question, String queryMode) {
        if (!enabled) return "";
        try {
            var body = Map.of("question", question, "mode", queryMode);
            var resp = rest.postForEntity(url + "/query", body, QueryResponse.class);
            var answer = resp.getBody();
            if (answer == null) return "";
            log.info("LightRAG 查询完成, 回答长度: {} 字符", answer.answer() != null ? answer.answer().length() : 0);
            return answer.answer() != null ? answer.answer() : "";
        } catch (Exception e) {
            log.warn("LightRAG 查询失败: {}", e.getMessage());
            return "";  // 失败时返回空字符串，主逻辑可 fallback
        }
    }

    /** LightRAG /query 端点的响应结构 */
    private record QueryResponse(String answer) {}
}
