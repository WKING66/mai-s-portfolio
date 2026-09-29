package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 仅供 OpenAPI 描述 R<AdminProfileVo> 的具体 data 类型。 */
@Schema(description = "站长资料接口的统一响应结构")
public record AdminProfileApiVo(
    @Schema(description = "成功为 OK；失败为稳定错误码", example = "OK") String code,
    @Schema(description = "面向调用方的提示", example = "成功") String message,
    @Schema(description = "资料快照；错误响应时为 null") AdminProfileVo data,
    @Schema(description = "字段或请求问题；无问题时为空数组") List<String> details
) {
}
