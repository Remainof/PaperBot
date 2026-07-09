package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 重排序服务
 * <p>
 * 用 LLM 作为 reranker，对 Milvus 向量检索的结果进行语义重排序。
 * 向量搜索先召回 topK=20 个候选片段，reranker 精排后保留最相关的 topK 个给 LLM 生成。
 * <p>
 * 此服务为可选依赖——如果不注入，RagService 直接使用 Milvus 的排序结果。
 */
@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    @Value("${dashscope.api.key}")         private String apiKey;
    @Value("${rag.model:qwen3-max}")       private String model;

    private Generation generation;

    @PostConstruct
    void init() {
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";
        generation = new Generation();
        log.info("Reranker 初始化完成, model={}", model);
    }

    /**
     * 对候选搜索结果重排序，返回 topK 个最相关的片段。
     * <p>
     * 方法：用 LLM 判断每个片段与问题的相关性，按 LLM 给出的相关性分数排序。
     * 相比仅依赖 embedding 向量距离，side-by-side 比较能更准确判断语义相关性。
     */
    public List<VectorSearchService.SearchResult> rerank(
            String question,
            List<VectorSearchService.SearchResult> candidates,
            int topK) {

        if (candidates == null || candidates.isEmpty()) return List.of();
        if (candidates.size() <= topK) return candidates;

        log.info("Reranker: 对 {} 个候选片段重排序, topK={}", candidates.size(), topK);

        // 对每个片段用 LLM 打分
        var scored = new ArrayList<ScoredResult>();
        for (int i = 0; i < candidates.size(); i++) {
            var r = candidates.get(i);
            int score = scoreRelevance(question, r.getContent(), i);
            scored.add(new ScoredResult(r, score));
            log.debug("  [{}/{}] 相关性评分={}: {}", i + 1, candidates.size(), score, truncate(r.getContent(), 60));
        }

        // 按 LLM 给出的相关性分数降序排列，取 topK
        scored.sort(Comparator.<ScoredResult>comparingInt(s -> s.score).reversed());
        return scored.stream().limit(topK).map(s -> s.result).toList();
    }

    /**
     * 用 LLM 判断单个片段与问题的相关性，返回 1-5 的整数分数。
     */
    private int scoreRelevance(String question, String content, int index) {
        var prompt = """
你是一个论文检索质量评估员。请判断以下文本片段与用户问题的**相关性**。

评分标准（1-5分）：
1 = 完全不相关
2 = 轻微相关，但不太有用
3 = 部分相关，包含一些有用信息
4 = 高度相关，直接回答了问题的重要方面
5 = 极其相关，直接包含问题的核心答案

只返回一个数字，不要解释。

用户问题：%s

文本片段：%s
""".formatted(question, content);

        try {
            var msg = Message.builder().role(Role.USER.getValue()).content(prompt).build();
            var param = GenerationParam.builder()
                    .apiKey(apiKey).model(model).resultFormat("message")
                    .temperature(0.1f).topP(0.5d)     // 低 temperature 保证稳定打分
                    .incrementalOutput(false)
                    .messages(List.of(msg))
                    .build();
            var result = generation.call(param);
            var text = extractContent(result).trim();

            // 解析数字
            int score = 3; // 默认中等
            if (text.matches(".*[1-5].*")) {
                // 取第一个出现的 1-5 数字
                var m = java.util.regex.Pattern.compile("[1-5]").matcher(text);
                if (m.find()) score = Integer.parseInt(m.group());
            }
            return score;
        } catch (Exception e) {
            log.warn("Reranker 打分失败 (index={}), 使用默认分 3", index);
            return 3;
        }
    }

    private String extractContent(GenerationResult result) {
        if (result.getOutput() != null && result.getOutput().getChoices() != null && !result.getOutput().getChoices().isEmpty()) {
            var c = result.getOutput().getChoices().get(0).getMessage().getContent();
            return c != null ? c : "";
        }
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record ScoredResult(VectorSearchService.SearchResult result, int score) {}
}
