package dev.amai.portfolio.asset.api;

import java.util.Optional;

/** 媒体资产受控读取边界；调用方必须先按自身发布规则完成公开授权判断。 */
public interface AssetContentService {
    /** 读取已就绪且类型一致的对象；调用方负责关闭返回内容。 */
    Optional<AssetContentData> openReady(Long assetId, AssetType expectedType);
}
