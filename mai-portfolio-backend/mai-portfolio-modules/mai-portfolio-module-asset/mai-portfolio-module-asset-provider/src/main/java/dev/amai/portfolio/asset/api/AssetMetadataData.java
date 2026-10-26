package dev.amai.portfolio.asset.api;

/** 绑定校验所需事实；不含对象键和签名，不触发外部存储依赖。 */
public record AssetMetadataData(
        Long id,

        AssetType type,

        AssetUsage usage,

        Long uploaderAccountId) {}
