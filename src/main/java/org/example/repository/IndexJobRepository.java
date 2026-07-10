package org.example.repository;

import org.apache.ibatis.annotations.*;
import org.example.entity.IndexJobEntity;

import java.util.List;

/**
 * 索引任务 Mapper —— 操作 index_job 表。
 */
@Mapper
public interface IndexJobRepository {

    @Insert("INSERT INTO index_job (job_id, document_id, source_path, file_hash, index_version, " +
            "status, total_chunks, completed_chunks, error_message, created_at, updated_at) " +
            "VALUES (#{jobId}, #{documentId}, #{sourcePath}, #{fileHash}, #{indexVersion}, " +
            "#{status}, #{totalChunks}, #{completedChunks}, #{errorMessage}, #{createdAt}, #{updatedAt})")
    int insert(IndexJobEntity entity);

    @Select("SELECT * FROM index_job WHERE job_id = #{jobId}")
    @Results(id = "indexJobMap", value = {
            @Result(column = "job_id", property = "jobId"),
            @Result(column = "document_id", property = "documentId"),
            @Result(column = "source_path", property = "sourcePath"),
            @Result(column = "file_hash", property = "fileHash"),
            @Result(column = "index_version", property = "indexVersion"),
            @Result(column = "total_chunks", property = "totalChunks"),
            @Result(column = "completed_chunks", property = "completedChunks"),
            @Result(column = "error_message", property = "errorMessage"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    IndexJobEntity findById(String jobId);

    @Update("UPDATE index_job SET status = #{status}, error_message = #{errorMessage}, updated_at = NOW(6) WHERE job_id = #{jobId}")
    int updateStatus(@Param("jobId") String jobId, @Param("status") String status, @Param("errorMessage") String errorMessage);

    @Update("UPDATE index_job SET completed_chunks = #{completedChunks}, status = #{status}, updated_at = NOW(6) WHERE job_id = #{jobId}")
    int updateProgress(@Param("jobId") String jobId, @Param("completedChunks") int completedChunks, @Param("status") String status);

    @Update("UPDATE index_job SET total_chunks = #{totalChunks}, updated_at = NOW(6) WHERE job_id = #{jobId}")
    int updateTotalChunks(@Param("jobId") String jobId, @Param("totalChunks") int totalChunks);

    @Select("SELECT * FROM index_job WHERE document_id = #{documentId} AND file_hash = #{fileHash} ORDER BY created_at DESC")
    @ResultMap("indexJobMap")
    List<IndexJobEntity> findByDocumentAndHash(@Param("documentId") String documentId, @Param("fileHash") String fileHash);

    @Select("SELECT COUNT(*) FROM index_job WHERE document_id = #{documentId} AND file_hash = #{fileHash} AND status = 'SUCCEEDED'")
    boolean existsSucceeded(@Param("documentId") String documentId, @Param("fileHash") String fileHash);
}
