package dev.amai.portfolio.portfolio.service;

import dev.amai.portfolio.asset.api.AssetContentData;

/** 公开个人资料媒体的授权与读取边界。 */
public interface ProfileMediaService {
    /** 仅允许读取当前站点配置实际引用的公开头像或简历。 */
    AssetContentData openPublicMedia(Long assetId);
}
