package dev.amai.portfolio.blog.entity.vo;

import java.time.LocalDateTime;

/** 导入追踪对外投影；issuesJson 为安全结构化诊断，不含源文件或 OSS 凭据。 */
public record DocumentImportTaskVo(
        long id,

        Long documentId,

        String requestId,

        String originalFilename,

        long sourceSize,

        String sourceSha256,

        String status,

        String issuesJson,

        LocalDateTime createdAt,

        LocalDateTime updatedAt,

        LocalDateTime finishedAt) {}
