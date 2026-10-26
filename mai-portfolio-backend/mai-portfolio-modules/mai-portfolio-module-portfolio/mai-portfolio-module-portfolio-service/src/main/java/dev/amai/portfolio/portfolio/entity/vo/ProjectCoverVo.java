package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "项目封面引用；对象地址不随项目上下架而变化")
public record ProjectCoverVo(
        Long assetId,

        @Schema(description = "OSS 签名直连地址")
        String url,

        String alt,

        @Schema(description = "地址有效期；到期后重新读取业务 API")
        Instant expiresAt
) { }
