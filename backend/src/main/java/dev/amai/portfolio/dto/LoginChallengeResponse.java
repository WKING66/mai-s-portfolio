package dev.amai.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "一次性 RSA-OAEP 登录加密凭证")
public record LoginChallengeResponse(
    @Schema(description = "登录凭证 ID，仅可使用一次") String challengeId,
    @Schema(description = "RSA 公钥的 SPKI DER 标准 Base64 编码") String publicKey,
    @Schema(description = "浏览器导入公钥所用算法", example = "RSA-OAEP-256") String algorithm,
    @Schema(description = "过期时间（UTC）") Instant expiresAt
) {
}
