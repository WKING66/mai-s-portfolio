package dev.amai.portfolio.asset.entity.vo;

import dev.amai.portfolio.asset.api.AssetUsage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "就绪资产及 OSS 临时直连地址；上传不自动绑定业务对象")
public record AssetVo(
        @Schema(description = "业务绑定使用的资产 ID") Long id,
        @Schema(description = "浏览器直接访问的签名 URL，不再经过后端代理") String resourceUrl,
        @Schema(description = "签名到期时间（UTC），到期后重新读取业务资料取得新地址") Instant expiresAt,
        String contentType,
        long byteSize,
        AssetUsage usage) {}
