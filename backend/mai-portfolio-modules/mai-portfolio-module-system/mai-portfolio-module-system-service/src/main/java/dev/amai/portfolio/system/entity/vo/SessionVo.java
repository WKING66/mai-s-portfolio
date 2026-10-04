package dev.amai.portfolio.system.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "用户会话状态")
public record SessionVo(
    @Schema(description = "是否已登录") boolean loggedIn,
    @Schema(description = "用户名；未登录时为 null", example = "owner") String username,
    @Schema(description = "写操作所需 CSRF 令牌；未登录时为 null") String csrfToken
) {
}
