package dev.amai.portfolio.blog.entity.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 导入来源与安全诊断记录；不保存文件字节或完整带签名 URL。 */
@Getter
@Setter
@TableName("document_import_task")
public class DocumentImportTaskDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long documentId;

    private Integer sourceFormat;

    private String originalFilename;

    private Long uploaderAccountId;

    private String requestId;

    private Long sourceSize;

    private String sourceSha256;

    private String status;

    private String issuesJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;
}
