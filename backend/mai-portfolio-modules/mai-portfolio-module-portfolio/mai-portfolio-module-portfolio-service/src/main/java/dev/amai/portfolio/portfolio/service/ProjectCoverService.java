package dev.amai.portfolio.portfolio.service;

import dev.amai.portfolio.asset.api.AssetContentData;

/** 公开封面授权：项目已发布且仍引用该资源后，才允许 Asset 打开字节。 */
public interface ProjectCoverService {
    AssetContentData openPublicCover(Long projectId);
}
