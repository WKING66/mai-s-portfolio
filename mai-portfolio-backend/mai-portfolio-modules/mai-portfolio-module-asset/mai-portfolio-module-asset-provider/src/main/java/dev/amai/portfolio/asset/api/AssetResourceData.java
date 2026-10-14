package dev.amai.portfolio.asset.api;

import java.time.Instant;

/** 资产域的就绪元数据与临时访问地址；业务引用与上传者归属不由 URL 决定。 */
public record AssetResourceData(Long id, AssetType type, AssetUsage usage, Long uploaderAccountId,
        String originalFilename, String contentType, long byteSize, String resourceUrl, Instant expiresAt) { }
