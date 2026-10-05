package dev.amai.portfolio.system.entity.request;

import dev.amai.portfolio.system.constant.SystemMessageConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 注册只接收账号与密文；账号类型、状态由服务端固定，不能绑定客户端角色。 */
@Schema(description = "普通用户注册请求；密码沿用登录的固定 RSA 公钥加密")
public record RegisterRequest(
    @NotBlank(message = SystemMessageConstants.LOGIN_USERNAME_REQUIRED)
    @Size(max = 64, message = SystemMessageConstants.LOGIN_USERNAME_TOO_LONG)
    @Schema(description = "用户名，3–64 位 ASCII 字母、数字、下划线或连字符；不区分大小写判重",
        example = "portfolio_reader", requiredMode = Schema.RequiredMode.REQUIRED)
    String username,
    @NotBlank(message = SystemMessageConstants.LOGIN_CIPHERTEXT_REQUIRED)
    @Size(max = 512, message = SystemMessageConstants.LOGIN_CIPHERTEXT_INVALID)
    @Schema(description = "RSA-OAEP SHA-256 加密密码的标准 Base64；原密码至少 12 个 Unicode 字符，"
        + "UTF-8 不超过 190 字节；不接受明文或密码摘要", format = "byte",
        accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED)
    String encryptedPassword
) {
    /** 先规范化用户名，再执行 Bean Validation；密码始终保持原始密文。 */
    public RegisterRequest {
        username = username == null ? null : username.trim();
    }
}
