package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class FileUploadRes {
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String paperTitle;
    private String docType;

    /** 异步模式：任务ID */
    private String jobId;
    /** 异步模式：任务状态 */
    private String status;

    public FileUploadRes() {}
    public FileUploadRes(String fileName, String filePath, Long fileSize) { this.fileName = fileName; this.filePath = filePath; this.fileSize = fileSize; }
}
