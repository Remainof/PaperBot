package org.example.service;

import org.example.dto.IndexMessage;
import org.example.dto.IndexMessage.ChunkItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Embedding 批处理工具 —— 按 chunk 数量和 token 预算切分批次。
 */
@Component
public class EmbeddingBatcher {

    @Value("${indexing.embedding.batch-size:10}")
    private int batchSize;

    @Value("${indexing.embedding.max-tokens-per-batch:24000}")
    private int maxTokensPerBatch;

    /**
     * 将 ChunkItem 按 batch-size 切分成多个 batch。
     * 每个 batch 创建一个 embedding 消息。
     */
    public List<IndexMessage> splitIntoBatches(String jobId, String documentId,
                                                String indexVersion, List<ChunkItem> allChunks) {
        List<IndexMessage> batches = new ArrayList<>();
        int batchIndex = 0;
        int size = Math.min(batchSize, allChunks.size());

        for (int i = 0; i < allChunks.size(); i += size) {
            int end = Math.min(i + size, allChunks.size());
            var batch = allChunks.subList(i, end);
            var batchId = jobId + "_b" + batchIndex;
            batches.add(IndexMessage.embedding(jobId, documentId, indexVersion, batchId, new ArrayList<>(batch), 1));
            batchIndex++;
        }

        return batches;
    }

    /**
     * 计算分片内容的 SHA-256 哈希。
     */
    public String computeChunkHash(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    /**
     * 根据 content 长度估算 token 数（简单估算法：字符数 * 0.25）。
     */
    public int estimateTokens(String content) {
        return (int) Math.ceil(content.length() * 0.25);
    }
}
