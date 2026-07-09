package org.example.service;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.data.SparseFloatVec;
import io.milvus.v2.service.vector.request.ranker.WeightedRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.Getter;
import lombok.Setter;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired private MilvusClientV2 client;
    @Autowired private VectorEmbeddingService embeddingService;

    /**
     * Hybrid Search：同时用稠密向量（语义）+ 稀疏向量（BM25 关键词）搜索，
     * 通过 WeightedRanker 融合结果。
     * <p>
     * 搜索流程：
     * 1. 生成稠密向量 (text-embedding-v4 → List&lt;Float&gt;)
     * 2. 生成稀疏向量 (BM25 token hash → Map&lt;Long,Float&gt;)
     * 3. 在 Milvus 中同时跑 dense search + sparse search
     * 4. 用 WeightedRanker 按权重融合
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        var queryDense = embeddingService.generateEmbedding(query);
        var querySparse = SparseVectorGenerator.generate(query);

        log.debug("Hybrid Search: query=\"{}\", denseDim={}, sparseTokens={}, topK={}",
                truncate(query, 50), queryDense.size(), querySparse.size(), topK);

        // 1. 稠密向量搜索
        var denseReq = AnnSearchReq.builder()
                .vectorFieldName(MilvusConstants.DENSE_FIELD)
                .vectors(List.of((BaseVector) new FloatVec(queryDense)))
                .metricType(IndexParam.MetricType.L2)
                .params("{\"nprobe\":10}")
                .build();

        // 2. 稀疏向量搜索（BM25）
        var sparseReq = AnnSearchReq.builder()
                .vectorFieldName(MilvusConstants.SPARSE_FIELD)
                .vectors(List.of((BaseVector) new SparseFloatVec(new TreeMap<>(querySparse))))
                .metricType(IndexParam.MetricType.BM25)
                .build();

        // 3. 混合搜索 + WeightedRanker 融合
        var hybridReq = HybridSearchReq.builder()
                .collectionName(MilvusConstants.MILVUS_COLLECTION_NAME)
                .searchRequests(List.of(denseReq, sparseReq))
                .outFields(List.of("id", "content", "metadata"))
                .limit(topK)
                .ranker(WeightedRanker.builder()
                        .weights(List.of(
                                (float) MilvusConstants.HYBRID_DENSE_WEIGHT,
                                (float) MilvusConstants.HYBRID_SPARSE_WEIGHT))
                        .build())
                .build();

        SearchResp resp = client.hybridSearch(hybridReq);

        var results = new ArrayList<SearchResult>();
        for (var group : resp.getSearchResults()) {
            for (var sr : group) {
                var result = new SearchResult();
                result.setId(sr.getId() != null ? sr.getId().toString() : null);
                result.setContent((String) sr.getEntity().get("content"));
                float score = sr.getScore() != null ? sr.getScore() : 0f;
                result.setScore(score);
                var md = sr.getEntity().get("metadata");
                if (md != null) result.setMetadata(md.toString());
                results.add(result);
            }
        }

        log.info("Hybrid Search 完成: query=\"{}\", topK={}, 命中={}",
                truncate(query, 50), topK, results.size());
        return results;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Getter @Setter
    public static class SearchResult {
        private String id;
        private String content;
        private float score;
        private String metadata;
    }
}
