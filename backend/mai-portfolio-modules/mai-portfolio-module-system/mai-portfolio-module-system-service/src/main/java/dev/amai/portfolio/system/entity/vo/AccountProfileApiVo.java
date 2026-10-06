package dev.amai.portfolio.system.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 仅供 OpenAPI 展示 R<AccountProfileVo> 的具体 data 类型。 */
@Schema(description = "个人中心资料接口响应")
public record AccountProfileApiVo(
    @Schema(description = "成功为 OK；失败为稳定错误码") String code,
    @Schema(description = "操作提示") String message,
    @Schema(description = "当前账号本人资料") AccountProfileVo data,
    @Schema(description = "参数问题清单") List<String> details
) {
}
