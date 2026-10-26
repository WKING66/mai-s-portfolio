package dev.amai.portfolio.blog.entity.domain;

import dev.amai.portfolio.blog.enums.ContentStage;

/** 列表只加载标题、摘要和槽位状态，不读取长正文。 */
public record DocumentSummary(
        long documentId,

        long contentId,

        Long revision,

        ContentStage stage,

        boolean hasDraft,

        boolean hasPublished,

        String title,

        String summary) {}
