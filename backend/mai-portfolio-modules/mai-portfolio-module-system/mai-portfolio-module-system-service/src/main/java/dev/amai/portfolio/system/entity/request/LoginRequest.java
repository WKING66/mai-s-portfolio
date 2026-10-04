package dev.amai.portfolio.system.entity.request;

import dev.amai.portfolio.system.constant.SystemMessageConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "用户密文登录请求；浏览器使用部署配置中的固定公钥")
public record LoginRequest(
    @NotBlank(message = SystemMessageConstants.LOGIN_USERNAME_REQUIRED)
    @Size(max = 64, message = SystemMessageConstants.LOGIN_USERNAME_TOO_LONG)
    @Schema(description = "用户名", example = "owner", requiredMode = Schema.RequiredMode.REQUIRED)
    String username,
    @NotBlank(message = SystemMessageConstants.LOGIN_CIPHERTEXT_REQUIRED)
    @Size(max = 512, message = SystemMessageConstants.LOGIN_CIPHERTEXT_INVALID)
    @Schema(description = "RSA-OAEP SHA-256 加密后的密码，标准 Base64；不接受明文密码",
        format = "byte", accessMode = Schema.AccessMode.WRITE_ONLY,
        requiredMode = Schema.RequiredMode.REQUIRED)
    String encryptedPassword
) {
}
