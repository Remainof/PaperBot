package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 项目启动时自动创建 index_job 和 index_chunk 两张表（如果不存在）。
 */
@Component
public class DatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    private final JdbcTemplate jdbc;

    public DatabaseInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        createIndexJobTable();
        createIndexChunkTable();
        log.info("数据库表初始化完成");
    }

    private void createIndexJobTable() {
        var sql = """
                CREATE TABLE IF NOT EXISTS index_job (
                    job_id              VARCHAR(64)   PRIMARY KEY COMMENT '任务ID，UUID',
                    document_id         VARCHAR(128)  NOT NULL     COMMENT '文档标识（文件名或自定义ID）',
                    source_path         VARCHAR(1024) NOT NULL     COMMENT '文件在磁盘上的存储路径',
                    file_hash           CHAR(64)      NOT NULL     COMMENT '文件内容的 SHA-256，用于判重',
                    index_version       VARCHAR(64)   NOT NULL     COMMENT '索引版本号（每次重传递增）',
                    status              VARCHAR(32)   NOT NULL     COMMENT '任务状态：PENDING/PARSING/INDEXING/SUCCEEDED/FAILED',
                    total_chunks        INT           NOT NULL DEFAULT 0 COMMENT '该论文的总分片数',
                    completed_chunks    INT           NOT NULL DEFAULT 0 COMMENT '已成功索引的分片数',
                    error_message       TEXT          NULL         COMMENT '失败时的错误信息',
                    created_at          DATETIME(6)   NOT NULL     COMMENT '任务创建时间',
                    updated_at          DATETIME(6)   NOT NULL     COMMENT '最后更新时间',
                    UNIQUE KEY uk_doc_file_version (document_id, file_hash, index_version) COMMENT '同一文档同版本不重复',
                    KEY idx_status_created (status, created_at) COMMENT '按状态+时间查询'
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='索引任务表，记录每篇论文的索引进度'
                """;
        jdbc.execute(sql);
        log.info("表 index_job 已就绪");
    }

    private void createIndexChunkTable() {
        var sql = """
                CREATE TABLE IF NOT EXISTS index_chunk (
                    job_id              VARCHAR(64)   NOT NULL     COMMENT '所属任务ID，关联 index_job.job_id',
                    index_version       VARCHAR(64)   NOT NULL     COMMENT '索引版本号',
                    chunk_index         INT           NOT NULL     COMMENT '分片序号（从 0 开始）',
                    content_hash        CHAR(64)      NOT NULL     COMMENT '分片内容的 SHA-256，用于 embedding 缓存判重',
                    content_length      INT           NOT NULL DEFAULT 0 COMMENT '分片字符数',
                    chunk_status        VARCHAR(32)   NOT NULL     COMMENT '分片状态：PENDING/INDEXED/FAILED',
                    milvus_id           VARCHAR(128)  NULL         COMMENT 'Milvus 中该分片的 ID',
                    retry_count         INT           NOT NULL DEFAULT 0 COMMENT '已重试次数，超过上限投递 DLQ',
                    error_message       TEXT          NULL         COMMENT '失败原因',
                    created_at          DATETIME(6)   NOT NULL     COMMENT '分片记录创建时间',
                    updated_at          DATETIME(6)   NOT NULL     COMMENT '最后更新时间',
                    PRIMARY KEY (job_id, index_version, chunk_index) COMMENT '一个分片在一个版本中唯一',
                    KEY idx_status (job_id, index_version, chunk_status) COMMENT '按任务+状态过滤'
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分片状态表，保存每个分片的处理状态、内容哈希和 Milvus ID'
                """;
        jdbc.execute(sql);
        log.info("表 index_chunk 已就绪");
    }
}
