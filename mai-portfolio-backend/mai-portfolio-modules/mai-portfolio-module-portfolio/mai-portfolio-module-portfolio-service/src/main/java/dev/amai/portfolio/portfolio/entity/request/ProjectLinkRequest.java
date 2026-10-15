package dev.amai.portfolio.portfolio.entity.request;

import dev.amai.portfolio.portfolio.constant.ProjectConstants;
import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 外链编辑项；type 为 CODE/DEMO/DOCUMENTATION/OTHER。 */
@Schema(description = "外链编辑项；type 为 CODE/DEMO/DOCUMENTATION/OTHER")
public record ProjectLinkRequest(
        @NotNull(message = ProjectMessageConstants.INVALID_LINKS)
        String type,

        @Size(max = ProjectConstants.MAX_LINK_LABEL_LENGTH, message = ProjectMessageConstants.INVALID_FIELDS)
        String label,

        @NotNull(message = ProjectMessageConstants.INVALID_LINKS)
        @Size(max = ProjectConstants.MAX_URL_LENGTH, message = ProjectMessageConstants.INVALID_FIELDS)
        String url,

        @NotNull(message = ProjectMessageConstants.INVALID_LINKS)
        Boolean visible,

        @NotNull(message = ProjectMessageConstants.INVALID_LINKS)
        @Min(value = 0, message = ProjectMessageConstants.INVALID_LINKS)
        Integer sortOrder
) { }
