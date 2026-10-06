package dev.amai.portfolio.system.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "当前登录账号本人资料；无角色、密码摘要或对象存储键")
public record AccountProfileVo(
    @Schema(description = "只读登录用户名", example = "portfolio_reader") String username,
    @Schema(description = "显示昵称；未设置时为用户名", example = "阿霾") String nickname,
    @Schema(description = "受 Cookie 保护的本人头像读取地址；未上传时为 null") String avatarUrl
) {
}
