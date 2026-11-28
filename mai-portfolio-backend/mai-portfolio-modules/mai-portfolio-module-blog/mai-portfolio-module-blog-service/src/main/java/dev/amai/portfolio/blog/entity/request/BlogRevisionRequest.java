package dev.amai.portfolio.blog.entity.request;

import static dev.amai.portfolio.document.constant.DocumentPolicy.REQUEST_ID_PATTERN;

import dev.amai.portfolio.document.api.DocumentPublicationOptions;
import jakarta.validation.constraints.*;

/** 状态动作与删除共用写修订；同一请求标识只允许重试相同动作。 */
public record BlogRevisionRequest(
        @NotNull
        @Positive
        Long revision,

        @NotBlank
        @Pattern(regexp = REQUEST_ID_PATTERN)
        String requestId,

        @Min(1)
        @Max(3)
        Integer visibilityLevel,

        Boolean aiIndexEnabled,

        @Min(1)
        @Max(2)
        Integer indexBuildMode,

        Boolean purgeVectorPayload) {
    public BlogRevisionRequest(Long revision, String requestId) {
        this(revision, requestId, null, null, null, null);
    }

    public DocumentPublicationOptions publicationOptions(boolean allowPurge) {
        return new DocumentPublicationOptions(visibilityLevel, aiIndexEnabled, indexBuildMode,
                allowPurge && Boolean.TRUE.equals(purgeVectorPayload));
    }
}
