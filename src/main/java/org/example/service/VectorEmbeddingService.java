package org.example.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import org.example.config.EmbeddingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class VectorEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(VectorEmbeddingService.class);

    @Value("${dashscope.api.key}")       private String apiKey;
    @Value("${dashscope.embedding.model}") private String model;

    private TextEmbedding textEmbedding;

    @PostConstruct
    void init() {
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("your-api-key")) {
            throw new IllegalStateException("请设置环境变量 DASHSCOPE_API_KEY");
        }
        Constants.apiKey = apiKey;
        textEmbedding = new TextEmbedding();
        log.info("Embedding 服务初始化完成, model={}", model);
    }

    /** 将文本转为 1024 维向量 */
    public List<Float> generateEmbedding(String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("内容不能为空");

        var param = TextEmbeddingParam.builder()
                .model(model)
                .texts(Collections.singletonList(content))
                .build();
        try {
            var result = textEmbedding.call(param);
            return toFloats(result);
        } catch (Exception e) {
            throw new EmbeddingException("文本向量化失败", e);
        }
    }

    /** 批量 embedding */
    public List<List<Float>> generateEmbeddings(List<String> contents) {
        if (contents == null || contents.isEmpty()) return List.of();
        var param = TextEmbeddingParam.builder().model(model).texts(contents).build();
        try {
            var result = textEmbedding.call(param);
            var output = result.getOutput();
            if (output == null || output.getEmbeddings() == null) throw new EmbeddingException("API 返回空结果");
            return output.getEmbeddings().stream().map(e -> toFloats(e.getEmbedding())).toList();
        } catch (Exception e) {
            throw new EmbeddingException("批量文本向量化失败", e);
        }
    }

    /** 计算余弦相似度 */
    public float cosineSimilarity(List<Float> a, List<Float> b) {
        if (a.size() != b.size()) throw new IllegalArgumentException("维度不匹配");
        float dot = 0, n1 = 0, n2 = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            n1 += a.get(i) * a.get(i);
            n2 += b.get(i) * b.get(i);
        }
        return dot / (float) (Math.sqrt(n1) * Math.sqrt(n2));
    }

    // ===================== 内部 =====================

    private List<Float> toFloats(TextEmbeddingResult result) {
        var output = result.getOutput();
        if (output == null || output.getEmbeddings() == null || output.getEmbeddings().isEmpty())
            throw new EmbeddingException("API 返回空结果");
        return toFloats(output.getEmbeddings().get(0).getEmbedding());
    }

    private List<Float> toFloats(List<Double> doubles) {
        var floats = new ArrayList<Float>(doubles.size());
        for (var d : doubles) floats.add(d.floatValue());
        return floats;
    }
}
