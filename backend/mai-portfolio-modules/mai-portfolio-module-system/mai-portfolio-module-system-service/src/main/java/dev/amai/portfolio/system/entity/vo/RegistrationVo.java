package dev.amai.portfolio.system.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 注册不自动创建会话，不返回密码、内部账号 ID 或角色配置。 */
@Schema(description = "注册结果；请随后通过通用登录接口登录")
public record RegistrationVo(
    @Schema(description = "已创建的用户名", example = "portfolio_reader") String username
) {
}
