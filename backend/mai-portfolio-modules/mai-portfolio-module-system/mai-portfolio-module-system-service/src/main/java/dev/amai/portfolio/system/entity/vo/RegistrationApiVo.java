package dev.amai.portfolio.system.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 仅用于 OpenAPI 精确描述 R<RegistrationVo>。 */
@Schema(description = "普通用户注册的统一响应结构")
public record RegistrationApiVo(
    @Schema(description = "成功为 OK；失败为稳定错误码", example = "OK") String code,
    @Schema(description = "面向调用方的提示", example = "成功") String message,
    @Schema(description = "注册结果；失败为 null") RegistrationVo data,
    @Schema(description = "字段问题；成功为空数组") List<String> details
) {
}
