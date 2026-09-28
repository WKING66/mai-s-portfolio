package dev.amai.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 仅供 OpenAPI 描述 R<LoginChallengeResponse> 的具体 data 类型。 */
@Schema(description = "登录加密凭证接口的统一响应结构")
public record LoginChallengeApiEnvelope(
    @Schema(description = "成功为 OK；失败为稳定错误码", example = "OK") String code,
    @Schema(description = "面向调用方的提示", example = "成功") String message,
    @Schema(description = "一次性登录加密凭证") LoginChallengeResponse data,
    @Schema(description = "字段或请求问题；无问题时为空数组") List<String> details
) {
}
