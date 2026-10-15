package dev.amai.portfolio.blog.entity.vo;

import java.time.LocalDateTime;

/** 列表摘要不携带正文或资源 URL；version 与所属 MANAGE/PUBLIC 投影的版本语义一致。 */
public record BlogCardVo(
        long id,

        String slug,

        String title,

        String summary,

        String status,

        long version,

        LocalDateTime publishedAt) {}
