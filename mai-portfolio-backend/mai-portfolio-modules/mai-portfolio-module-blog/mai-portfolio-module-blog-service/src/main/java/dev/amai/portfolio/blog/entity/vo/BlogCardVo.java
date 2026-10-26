package dev.amai.portfolio.blog.entity.vo;

import dev.amai.portfolio.blog.enums.ContentStage;
import java.time.LocalDateTime;

/** 列表摘要与详情使用相同的槽位和修订语义。 */
public record BlogCardVo(
        long id,

        String slug,

        String title,

        String summary,

        String status,

        Long revision,

        long contentId,

        ContentStage stage,

        Boolean hasDraft,

        boolean hasPublished,

        LocalDateTime publishedAt) {}
