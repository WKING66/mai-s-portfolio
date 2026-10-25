package dev.amai.portfolio.blog.entity.request;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.REQUEST_ID_PATTERN;

import static dev.amai.portfolio.blog.constant.BlogPolicy.*;

import dev.amai.portfolio.validation.ValidationGroups;
import jakarta.validation.constraints.*;
import java.util.List;

/** 首次保存新文章的完整输入；已有文章的正文、属性更新使用各自独立请求。 */
public record BlogRequest(
        @NotNull(groups = ValidationGroups.Update.class)
        @Positive(groups = ValidationGroups.Update.class)
        Long revision,

        @NotBlank
        @Pattern(regexp = REQUEST_ID_PATTERN)
        String requestId,

        @Size(max = MAX_SLUG_LENGTH)
        @Pattern(regexp = SLUG_PATTERN, message = "文章标识仅支持小写字母、数字和短横线")
        String slug,

        @Size(max = MAX_TITLE_LENGTH)
        String title,

        @Size(max = MAX_SUMMARY_LENGTH)
        String summary,

        @NotNull
        String content,

        @NotNull
        @Size(max = MAX_TAGS)
        List<@NotNull @Positive Long> tagIds,

        boolean allowDownload,

        boolean remoteRightsConfirmed) {}
