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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class VectorSearchService {

    private static final Logger log = LoggerFactory.getLogger(VectorSearchService.class);

    @Autowired private MilvusClientV2 client;
    @Autowired private VectorEmbeddingService embeddingService;

    /** 搜索使用 v2 还是旧版 collection */
    @Value("${indexing.mode:sync}")
    private String indexingMode;

    /**
     * Hybrid Search：同时用稠密向量（语义）+ 稀疏向量（BM25 关键词）搜索，
     * 通过 WeightedRanker 融合结果。
     * <p>
     * 异步模式下会查询 papers_v2 并过滤 index_status=ACTIVE。
     */
    public List<SearchResult> searchSimilarDocuments(String query, int topK) {
        var queryDense = embeddingService.generateEmbedding(query);
        var querySparse = SparseVectorGenerator.generate(query);

        log.debug("Hybrid Search: query=\"{}\", denseDim={}, sparseTokens={}, topK={}",
                truncate(query, 50), queryDense.size(), querySparse.size(), topK);

        var expr = useV2() ? "index_status == \"ACTIVE\"" : "";

        var denseReq = AnnSearchReq.builder()
                .vectorFieldName(MilvusConstants.DENSE_FIELD)
                .vectors(List.of((BaseVector) new FloatVec(queryDense)))
                .metricType(IndexParam.MetricType.L2)
                .params("{\"nprobe\":10}")
                .expr(expr)
                .build();

        var sparseReq = AnnSearchReq.builder()
                .vectorFieldName(MilvusConstants.SPARSE_FIELD)
                .vectors(List.of((BaseVector) new SparseFloatVec(new TreeMap<>(querySparse))))
                .metricType(IndexParam.MetricType.BM25)
                .expr(expr)
                .build();

        var collectionName = useV2()
                ? MilvusConstants.MILVUS_COLLECTION_NAME_V2
                : MilvusConstants.MILVUS_COLLECTION_NAME;

        var hybridReq = HybridSearchReq.builder()
                .collectionName(collectionName)
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

        log.info("Hybrid Search 完成: query=\"{}\", topK={}, 命中={}, collection={}",
                truncate(query, 50), topK, results.size(), collectionName);
        return results;
    }

    private boolean useV2() {
        return "async".equalsIgnoreCase(indexingMode);
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
