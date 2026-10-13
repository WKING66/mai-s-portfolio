package dev.amai.portfolio.system.entity.request;

import dev.amai.portfolio.system.constant.AccountMessageConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "本人昵称修改；不接受账号 ID、角色或头像资源 ID")
public record AccountProfileRequest(
    @Size(max = 128, message = AccountMessageConstants.NICKNAME_INVALID)
    @Schema(description = "昵称最多 64 个 Unicode 字符；首尾空白去除，空值回退登录用户名", example = "阿霾")
    String nickname
) {
}
