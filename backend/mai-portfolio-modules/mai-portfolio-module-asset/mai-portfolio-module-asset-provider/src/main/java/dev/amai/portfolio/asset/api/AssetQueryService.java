package dev.amai.portfolio.asset.api;

/** 媒体资产域对外提供的只读能力。 */
public interface AssetQueryService {
    /** 判断资产是否已就绪且类型一致；公开 URL 由拥有发布语义的业务域生成。 */
    boolean isReady(Long assetId, AssetType expectedType);
}
