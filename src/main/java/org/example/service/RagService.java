package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Autowired private VectorSearchService searchService;
    @Autowired private RerankerService reranker;

    @Value("${dashscope.api.key}")         private String apiKey;
    @Value("${rag.top-k:3}")               private int topK;
    @Value("${rag.model:qwen3-max}")       private String model;

    private Generation generation;

    @PostConstruct
    void init() {
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        generation = new Generation();
        log.info("RAG 初始化完成, model={}, topK={}", model, topK);
        if (reranker != null) log.info("Reranker 已启用");
    }

    // ===================== 非流式 =====================

    public String query(String question) { return query(question, List.of()); }

    public String query(String question, List<Map<String, String>> history) {
        var results = retrieve(question);
        if (results.isEmpty()) return "抱歉，知识库中未找到相关信息。";
        var prompt = buildPrompt(question, buildContext(results));
        return callLlm(prompt, history);
    }

    // ===================== 流式 =====================

    public void queryStream(String question, StreamCallback cb) { queryStream(question, List.of(), cb); }

    public void queryStream(String question, List<Map<String, String>> history, StreamCallback cb) {
        var results = retrieve(question);
        cb.onSearchResults(results);
        if (results.isEmpty()) {
            cb.onComplete("抱歉，知识库中未找到相关信息。", "");
            return;
        }
        var prompt = buildPrompt(question, buildContext(results));
        callLlmStream(prompt, history, cb);
    }

    // ===================== 检索（含可选的重排序） =====================

    private List<VectorSearchService.SearchResult> retrieve(String question) {
        // 如果启用了 reranker，多搜一些给 reranker 排序
        int searchTopK = (reranker != null) ? Math.max(topK * 4, 20) : topK;
        var results = searchService.searchSimilarDocuments(question, searchTopK);

        log.info("检索到 {} 个片段 (原始 topK={})", results.size(), searchTopK);
        for (var r : results) {
            var src = extractSource(r);
            log.debug("  [{:.4f}] {} — {}", r.getScore(), src, truncate(r.getContent(), 80));
        }

        if (reranker != null && results.size() > topK) {
            results = reranker.rerank(question, results, topK);
            log.info("重排序后保留 topK={}", topK);
        }

        return results.size() > topK ? results.subList(0, topK) : results;
    }

    // ===================== LLM 调用 =====================

    private String callLlm(String prompt, List<Map<String, String>> history) {
        var messages = toDashScopeMessages(history, prompt);
        var param = GenerationParam.builder()
                .apiKey(apiKey).model(model).resultFormat("message")
                .temperature(0.1f).topP(0.8d)
                .incrementalOutput(false).messages(messages).build();
        try {
            var result = generation.call(param);
            return extractContent(result);
        } catch (Exception e) {
            throw new RuntimeException("LLM 调用失败", e);
        }
    }

    private void callLlmStream(String prompt, List<Map<String, String>> history, StreamCallback cb) {
        var messages = toDashScopeMessages(history, prompt);
        var param = GenerationParam.builder()
                .apiKey(apiKey).model(model).resultFormat("message")
                .temperature(0.1f).topP(0.8d)
                .incrementalOutput(true).messages(messages).build();
        try {
            Flowable<GenerationResult> stream = generation.streamCall(param);
            var full = new StringBuilder();
            stream.blockingForEach(msg -> {
                var chunk = extractContent(msg);
                if (!chunk.isEmpty()) { full.append(chunk); cb.onContentChunk(chunk); }
            });
            cb.onComplete(full.toString(), "");
        } catch (Exception e) {
            cb.onError(e);
        }
    }

    // ===================== 工具方法 =====================

    private String buildContext(List<VectorSearchService.SearchResult> results) {
        var sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            sb.append("【参考资料 ").append(i + 1);
            var src = extractSource(r);
            if (src != null) sb.append(" — ").append(src);
            sb.append("】\n").append(r.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String question, String context) {
        return """
你是一个专业的论文研究助手。

请根据以下**参考资料**回答用户问题。**严格遵循以下规则**：
1. 只基于参考资料中的内容回答，**不要使用你的预训练知识**补充任何论文未提及的信息
2. 答案中的每个观点，必须在参考资料中有原文依据
3. 如果参考资料不足以回答问题，请明确说"参考资料中没有找到相关内容"
4. 区分"论文原文"和你的分析解读
5. 用中文回答，保留英文术语和专有名词
6. 对于关键结论，请在句末标注对应的参考资料编号，如 (参考资料 1)

参考资料：
%s

用户问题：%s
""".formatted(context, question);
    }

    private List<Message> toDashScopeMessages(List<Map<String, String>> history, String prompt) {
        var msgs = new ArrayList<Message>();
        for (var h : history) {
            var role = h.get("role");
            if ("user".equals(role)) msgs.add(Message.builder().role(Role.USER.getValue()).content(h.get("content")).build());
            else if ("assistant".equals(role)) msgs.add(Message.builder().role(Role.ASSISTANT.getValue()).content(h.get("content")).build());
        }
        msgs.add(Message.builder().role(Role.USER.getValue()).content(prompt).build());
        return msgs;
    }

    private String extractContent(GenerationResult result) {
        if (result.getOutput() != null && result.getOutput().getChoices() != null && !result.getOutput().getChoices().isEmpty()) {
            var c = result.getOutput().getChoices().get(0).getMessage().getContent();
            return c != null ? c : "";
        }
        return "";
    }

    private static String extractSource(VectorSearchService.SearchResult r) {
        if (r.getMetadata() == null) return null;
        // metadata JSON 格式: {... "_source": "./papers/xxx.pdf", ...}
        int idx = r.getMetadata().indexOf("\"_source\"");
        if (idx < 0) return null;
        int start = r.getMetadata().indexOf(':', idx) + 2;
        int end = r.getMetadata().indexOf(',', start);
        if (end < 0) end = r.getMetadata().indexOf('}', start);
        if (start < 0 || end < 0 || end <= start) return null;
        return r.getMetadata().substring(start, end).replaceAll("\"", "").trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ===================== Callback 接口 =====================

    public interface StreamCallback {
        default void onSearchResults(List<VectorSearchService.SearchResult> results) {}
        default void onReasoningChunk(String chunk) {}
        void onContentChunk(String chunk);
        void onComplete(String fullContent, String fullReasoning);
        default void onError(Exception e) {}
    }
}
