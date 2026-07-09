package org.example.config;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;

@Configuration
public class MilvusConfig {

    private static final Logger log = LoggerFactory.getLogger(MilvusConfig.class);

    @Autowired private MilvusProperties properties;

    private MilvusClientV2 client;

    @Bean
    public MilvusClientV2 milvusClient() {
        log.info("正在连接 Milvus: {}", properties.getAddress());
        var config = ConnectConfig.builder()
                .uri("http://" + properties.getAddress())
                .connectTimeoutMs(properties.getTimeout())
                .build();
        client = new MilvusClientV2(config);
        ensureCollection();
        log.info("Milvus 客户端初始化完成");
        return client;
    }

    @PreDestroy
    public void cleanup() {
        if (client != null) {
            try { client.close(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        log.info("Milvus 客户端已关闭");
    }

    private void ensureCollection() {
        var name = MilvusConstants.MILVUS_COLLECTION_NAME;
        if (client.hasCollection(HasCollectionReq.builder().collectionName(name).build())) {
            log.info("Collection 已存在: {}", name);
            return;
        }

        log.info("创建 collection: {}", name);

        var idField = CreateCollectionReq.FieldSchema.builder()
                .name("id").dataType(DataType.VarChar).maxLength(MilvusConstants.ID_MAX_LENGTH)
                .isPrimaryKey(true).autoID(false).build();
        var denseField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.DENSE_FIELD).dataType(DataType.FloatVector)
                .dimension(MilvusConstants.VECTOR_DIM).build();
        var sparseField = CreateCollectionReq.FieldSchema.builder()
                .name(MilvusConstants.SPARSE_FIELD).dataType(DataType.SparseFloatVector).build();
        var contentField = CreateCollectionReq.FieldSchema.builder()
                .name("content").dataType(DataType.VarChar).maxLength(MilvusConstants.CONTENT_MAX_LENGTH).build();
        var metaField = CreateCollectionReq.FieldSchema.builder()
                .name("metadata").dataType(DataType.JSON).build();

        var schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .fieldSchemaList(List.of(idField, denseField, sparseField, contentField, metaField))
                .build();

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(name)
                .description("论文知识库（Dense + Sparse Hybrid Search）")
                .collectionSchema(schema)
                .numShards(MilvusConstants.DEFAULT_SHARD_NUMBER)
                .build());

        // 稠密向量索引 — IVF_FLAT + L2
        var denseIndex = IndexParam.builder()
                .fieldName(MilvusConstants.DENSE_FIELD)
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.L2)
                .extraParams(Map.of("nlist", 128))
                .build();
        client.createIndex(CreateIndexReq.builder()
                .collectionName(name)
                .indexParams(List.of(denseIndex))
                .build());
        log.info("Dense 索引创建完成: field={}, type=IVF_FLAT", MilvusConstants.DENSE_FIELD);

        // 稀疏向量索引 — SPARSE_INVERTED_INDEX + BM25
        var sparseIndex = IndexParam.builder()
                .fieldName(MilvusConstants.SPARSE_FIELD)
                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                .metricType(IndexParam.MetricType.BM25)
                .extraParams(Map.of("inverted_index_algo", "DAAT_MAXSCORE"))
                .build();
        client.createIndex(CreateIndexReq.builder()
                .collectionName(name)
                .indexParams(List.of(sparseIndex))
                .build());
        log.info("Sparse 索引创建完成: field={}, type=SPARSE_INVERTED_INDEX", MilvusConstants.SPARSE_FIELD);

        log.info("Collection 创建完成: {}", name);
    }
}
