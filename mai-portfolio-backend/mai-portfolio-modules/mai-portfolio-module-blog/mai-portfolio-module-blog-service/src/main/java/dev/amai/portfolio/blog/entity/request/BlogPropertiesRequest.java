package dev.amai.portfolio.blog.entity.request;

import static dev.amai.portfolio.blog.constant.BlogPolicy.*;
import static dev.amai.portfolio.document.constant.DocumentPolicy.REQUEST_ID_PATTERN;

import dev.amai.portfolio.validation.ValidationGroups;
import dev.amai.portfolio.document.api.DocumentPublicationOptions;
import jakarta.validation.constraints.*;
import java.util.List;

/**
 * 完整属性表单，不接收正文。可空文字代表清空，tagIds 空数组代表清空标签；
 * allowDownload 必须显式提交，不能因漏传而关闭下载。slug 仅实际修改时校验 Update 组。
 */
public record BlogPropertiesRequest(
        @NotNull
        @Positive
        Long revision,

        @NotBlank
        @Pattern(regexp = REQUEST_ID_PATTERN)
        String requestId,

        @Size(max = MAX_TITLE_LENGTH)
        String title,

        @Size(max = MAX_SUMMARY_LENGTH)
        String summary,

        @Size(max = MAX_SLUG_LENGTH, groups = ValidationGroups.Update.class)
        @Pattern(
                regexp = SLUG_PATTERN,
                message = "文章标识仅支持小写字母、数字和短横线",
                groups = ValidationGroups.Update.class)
        String slug,

        @NotNull
        @Size(max = MAX_TAGS)
        List<@NotNull @Positive Long> tagIds,

        @NotNull
        Boolean allowDownload,

        @Min(1)
        @Max(3)
        Integer visibilityLevel,

        Boolean aiIndexEnabled,

        @Min(1)
        @Max(2)
        Integer indexBuildMode,

        Boolean purgeVectorPayload) {
    public BlogPropertiesRequest(Long revision, String requestId, String title, String summary, String slug,
                                 List<Long> tagIds, Boolean allowDownload) {
        this(revision, requestId, title, summary, slug, tagIds, allowDownload, null, null, null, null);
    }

    public DocumentPublicationOptions publicationOptions(boolean allowPurge) {
        return new DocumentPublicationOptions(visibilityLevel, aiIndexEnabled, indexBuildMode,
                allowPurge && Boolean.TRUE.equals(purgeVectorPayload));
    }
}
