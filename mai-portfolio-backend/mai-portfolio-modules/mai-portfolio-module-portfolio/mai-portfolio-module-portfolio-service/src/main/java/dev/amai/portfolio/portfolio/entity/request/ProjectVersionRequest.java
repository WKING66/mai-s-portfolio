package dev.amai.portfolio.portfolio.entity.request;

import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** 发布/下架请求，携带读取时的版本号。 */
@Schema(description = "发布/下架请求，携带读取时的版本号")
public record ProjectVersionRequest(
        @NotNull(message = ProjectMessageConstants.INVALID_FIELDS)
        @Min(value = 0, message = ProjectMessageConstants.INVALID_FIELDS)
        Long version
) { }
