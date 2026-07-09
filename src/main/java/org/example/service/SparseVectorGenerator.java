package org.example.service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * BM25 稀疏向量生成器。
 * <p>
 * 将文本拆分为 token，统计 TF (词频) 作为稀疏向量的权重。
 * Milvus 的 SPARSE_INVERTED_INDEX + BM25 metric 在搜索时会自动计算
 * 查询和文档之间的 BM25 分数，这里只需要提供 token → weight 的映射。
 * <p>
 * 注意：这里的 weight 使用归一化 TF（log(1+tf)），具体的 BM25 评分
 * 由 Milvus 在搜索时完成，本生成器只负责提取 token 和原始频率。
 */
public class SparseVectorGenerator {

    /** 英文 + 数字 token 模式 */
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z]{2,}|\\d+");
    /** 内置停用词（高频无意义词） */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "can", "could",
            "may", "might", "shall", "should", "to", "of", "in", "for", "on", "with",
            "at", "by", "from", "as", "into", "through", "during", "before", "after",
            "above", "below", "between", "out", "off", "over", "under", "again",
            "further", "then", "once", "here", "there", "when", "where", "why", "how",
            "all", "each", "every", "both", "few", "more", "most", "other", "some", "such",
            "no", "nor", "not", "only", "own", "same", "so", "than", "too", "very",
            "just", "because", "but", "and", "or", "if", "while", "although", "this",
            "that", "these", "those", "it", "its", "they", "them", "their", "what", "which",
            "we", "he", "she", "who", "whom", "about", "also", "within", "without",
            "one", "two", "new", "used", "using", "use", "using", "based", "show", "shown"
    );

    /**
     * 从文本生成稀疏向量。
     *
     * @param text 输入文本
     * @return Map&lt;tokenHash, weight&gt;
     *         tokenHash 是 token 字符串的 64 位 hash（用作 Milvus sparse vector 的 key）
     *         weight 是归一化词频 log(1+tf)
     */
    public static Map<Long, Float> generate(String text) {
        if (text == null || text.isBlank()) return Map.of();

        // 1. 分词并统计词频
        var tf = new HashMap<String, Integer>();
        var m = TOKEN.matcher(text.toLowerCase());
        while (m.find()) {
            var token = m.group();
            if (!STOP_WORDS.contains(token) && token.length() >= 2) {
                tf.merge(token, 1, Integer::sum);
            }
        }

        // 2. 中文部分：按 bi-gram 切分（2 个中文字作为一个 token）
        var chineseTokens = extractChineseBigrams(text);
        for (var token : chineseTokens) {
            tf.merge(token, 1, Integer::sum);
        }

        // 3. 转成 Milvus SparseFloatVector 格式
        // 使用 string hash 作为 key（Milvus 的 SparseFloatVector 接受 Map<Long,Float>）
        var sparse = new HashMap<Long, Float>();
        for (var entry : tf.entrySet()) {
            // weight = log(1 + tf) — 基本归一化
            var weight = (float) Math.log1p(entry.getValue());
            if (weight > 0) {
                sparse.put(hash(entry.getKey()), weight);
            }
        }

        return sparse;
    }

    /** 将字符串 hash 为 64 位 long 作为 Milvus sparse vector 的 key */
    private static long hash(String s) {
        long h = 0;
        for (char c : s.toCharArray()) {
            h = 31 * h + c;
        }
        return h;
    }

    /** 提取中文部分的 2-gram（滑动窗口，每 2 个中文字符组合） */
    private static List<String> extractChineseBigrams(String text) {
        var result = new ArrayList<String>();
        var chars = new ArrayList<Character>();
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {  // CJK 统一表意文字范围
                chars.add(c);
            }
        }
        for (int i = 0; i < chars.size() - 1; i++) {
            result.add("" + chars.get(i) + chars.get(i + 1));
        }
        return result;
    }
}
