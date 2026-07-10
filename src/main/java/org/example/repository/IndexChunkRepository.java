package org.example.repository;

import org.apache.ibatis.annotations.*;
import org.example.entity.IndexChunkEntity;

import java.util.List;

/**
 * 分片状态 Mapper —— 操作 index_chunk 表。
 */
@Mapper
public interface IndexChunkRepository {

    @Insert({"<script>",
            "INSERT INTO index_chunk (job_id, index_version, chunk_index, content_hash, " +
            "content_length, chunk_status, milvus_id, retry_count, error_message, created_at, updated_at) " +
            "VALUES ",
            "<foreach collection='list' item='c' separator=','>",
            "(#{c.jobId}, #{c.indexVersion}, #{c.chunkIndex}, #{c.contentHash}, " +
            "#{c.contentLength}, #{c.chunkStatus}, #{c.milvusId}, #{c.retryCount}, " +
            "#{c.errorMessage}, #{c.createdAt}, #{c.updatedAt})",
            "</foreach>",
            "</script>"})
    int batchInsert(@Param("list") List<IndexChunkEntity> chunks);

    @Select("SELECT * FROM index_chunk WHERE job_id = #{jobId} AND index_version = #{indexVersion} ORDER BY chunk_index")
    @Results(id = "indexChunkMap", value = {
            @Result(column = "job_id", property = "jobId"),
            @Result(column = "index_version", property = "indexVersion"),
            @Result(column = "chunk_index", property = "chunkIndex"),
            @Result(column = "content_hash", property = "contentHash"),
            @Result(column = "content_length", property = "contentLength"),
            @Result(column = "chunk_status", property = "chunkStatus"),
            @Result(column = "milvus_id", property = "milvusId"),
            @Result(column = "retry_count", property = "retryCount"),
            @Result(column = "error_message", property = "errorMessage"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    List<IndexChunkEntity> findByJobId(@Param("jobId") String jobId, @Param("indexVersion") String indexVersion);

    @Select("SELECT COUNT(*) FROM index_chunk WHERE job_id = #{jobId} AND index_version = #{indexVersion} AND chunk_status = #{chunkStatus}")
    int countByStatus(@Param("jobId") String jobId, @Param("indexVersion") String indexVersion, @Param("chunkStatus") String chunkStatus);

    @Update("UPDATE index_chunk SET chunk_status = 'INDEXED', milvus_id = #{milvusId}, updated_at = NOW(6) WHERE job_id = #{jobId} AND index_version = #{indexVersion} AND chunk_index = #{chunkIndex}")
    int markIndexed(@Param("jobId") String jobId, @Param("indexVersion") String indexVersion, @Param("chunkIndex") int chunkIndex, @Param("milvusId") String milvusId);

    @Update("UPDATE index_chunk SET chunk_status = 'FAILED', error_message = #{errorMessage}, updated_at = NOW(6) WHERE job_id = #{jobId} AND index_version = #{indexVersion} AND chunk_index = #{chunkIndex}")
    int markFailed(@Param("jobId") String jobId, @Param("indexVersion") String indexVersion, @Param("chunkIndex") int chunkIndex, @Param("errorMessage") String errorMessage);

    @Update("UPDATE index_chunk SET retry_count = retry_count + 1, updated_at = NOW(6) WHERE job_id = #{jobId} AND index_version = #{indexVersion} AND chunk_index = #{chunkIndex}")
    int incrementRetry(@Param("jobId") String jobId, @Param("indexVersion") String indexVersion, @Param("chunkIndex") int chunkIndex);
}
