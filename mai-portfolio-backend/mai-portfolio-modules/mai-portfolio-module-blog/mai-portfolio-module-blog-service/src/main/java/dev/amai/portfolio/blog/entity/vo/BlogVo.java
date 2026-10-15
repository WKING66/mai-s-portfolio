package dev.amai.portfolio.blog.entity.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同一博客的管理/公开投影：管理 version=lockVersion、content=当前草稿 Markdown；
 * 公开 version=已发布内容版本号、content=已发布 Markdown。临时资源地址单独投影，绝不回写正文。
 * previewIssue 说明保存已成功但资源地址生成暂时失败，不应提示用户重复保存。
 */
public record BlogVo(
        long id,

        String slug,

        String status,

        long version,

        long documentId,

        long documentVersionId,

        String title,

        String summary,

        String content,

        String previewIssue,

        List<DocumentResourceVo> resources,

        boolean allowDownload,

        LocalDateTime publishedAt,

        LocalDateTime updatedAt) {}
