package dev.amai.portfolio.portfolio.entity.request;

import dev.amai.portfolio.portfolio.constant.ProjectConstants;
import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 统一查询；view 默认为 PUBLIC，status 仅用于 MANAGE。 */
@Schema(description = "统一查询；view 默认为 PUBLIC，status 仅用于 MANAGE")
public record ProjectListRequest(
        @Schema(description = "列表视图，缺省 PUBLIC；MANAGE 要求有效站长权限", allowableValues = {"PUBLIC", "MANAGE"}, defaultValue = "PUBLIC")
        String view,

        @Schema(description = "页码，从 1 开始", minimum = "1", defaultValue = "1")
        @Min(value = ProjectConstants.FIRST_PAGE, message = ProjectMessageConstants.INVALID_QUERY)
        Integer page,

        @Schema(description = "每页数量：PUBLIC 默认 12、MANAGE 默认 20，最大 50", minimum = "1", maximum = "50")
        @Min(value = 1, message = ProjectMessageConstants.INVALID_QUERY)
        @Max(value = ProjectConstants.MAX_PAGE_SIZE, message = ProjectMessageConstants.INVALID_QUERY)
        Integer size,

        @Schema(description = "仅 MANAGE 可用的状态筛选；缺省返回全部", allowableValues = {"DRAFT", "PUBLISHED"})
        String status
) { }
