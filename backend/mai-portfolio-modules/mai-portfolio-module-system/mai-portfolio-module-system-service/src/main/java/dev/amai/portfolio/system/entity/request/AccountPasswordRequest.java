package dev.amai.portfolio.system.entity.request;

import dev.amai.portfolio.system.constant.SystemMessageConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "本人密码修改；两份密码分别使用当前登录 RSA-OAEP SHA-256 公钥加密")
public record AccountPasswordRequest(
    @NotBlank(message = SystemMessageConstants.LOGIN_CIPHERTEXT_REQUIRED)
    @Size(max = 512, message = SystemMessageConstants.LOGIN_CIPHERTEXT_INVALID)
    @Schema(description = "原密码的 RSA Base64 密文；原密码保持原样校验",
        format = "byte", accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
    String oldEncryptedPassword,
    @NotBlank(message = SystemMessageConstants.LOGIN_CIPHERTEXT_REQUIRED)
    @Size(max = 512, message = SystemMessageConstants.LOGIN_CIPHERTEXT_INVALID)
    @Schema(description = "新密码的 RSA Base64 密文；去首尾空白，只支持可见 ASCII，至少 12 位",
        format = "byte", accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
    String newEncryptedPassword
) {
}
