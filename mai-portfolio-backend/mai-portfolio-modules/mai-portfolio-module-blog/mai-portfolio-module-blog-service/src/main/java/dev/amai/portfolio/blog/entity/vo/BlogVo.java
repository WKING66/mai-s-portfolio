package dev.amai.portfolio.blog.entity.vo;

import dev.amai.portfolio.blog.enums.ContentStage;
import dev.amai.portfolio.system.api.TagData;
import java.time.LocalDateTime;
import java.util.List;

/** revision 仅管理投影返回；公开内容不泄漏草稿状态。临时 URL 不回写正文。 */
public record BlogVo(
        long id,

        String slug,

        String status,

        Long revision,

        long documentId,

        long contentId,

        ContentStage stage,

        Boolean hasDraft,

        boolean hasPublished,

        boolean resourcesReady,

        String title,

        String summary,

        String content,

        String previewIssue,

        List<DocumentResourceVo> resources,

        List<TagData> tags,

        boolean allowDownload,

        LocalDateTime publishedAt,

        LocalDateTime updatedAt) {}
