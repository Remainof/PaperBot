package org.example.constant;

public class MilvusConstants {

    // ===== 当前 Collection（旧版，保持兼容） =====
    public static final String MILVUS_DB_NAME = "default";
    public static final String MILVUS_COLLECTION_NAME = "papers";

    // ===== 新版 Collection（版本化，Kafka 异步索引） =====
    /** 新版 Collection 名称（含版本化字段） */
    public static final String MILVUS_COLLECTION_NAME_V2 = "papers_v2";

    /** 索引状态：STAGING（写入中）/ ACTIVE（已发布）/ FAILED（失败） */
    public static final String INDEX_STATUS_STAGING = "STAGING";
    public static final String INDEX_STATUS_ACTIVE = "ACTIVE";
    public static final String INDEX_STATUS_FAILED = "FAILED";

    public static final int VECTOR_DIM = 1024;
    public static final int ID_MAX_LENGTH = 256;
    public static final int CONTENT_MAX_LENGTH = 8192;
    public static final int DEFAULT_SHARD_NUMBER = 2;

    /** 稠密向量（语义）字段名 */
    public static final String DENSE_FIELD = "vector";
    /** 稀疏向量（关键词/BM25）字段名 */
    public static final String SPARSE_FIELD = "sparse_vector";

    /** Hybrid Search 权重：dense 占 0.7, sparse 占 0.3 */
    public static final double HYBRID_DENSE_WEIGHT = 0.7;
    public static final double HYBRID_SPARSE_WEIGHT = 0.3;

    private MilvusConstants() {}
}
