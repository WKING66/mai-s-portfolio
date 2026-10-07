package dev.amai.portfolio.asset.service.impl;

import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.ImageUploadPurpose;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.enums.MediaAssetType;
import dev.amai.portfolio.asset.enums.MediaStatus;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import org.springframework.stereotype.Service;

/** 媒体资产元数据查询实现；公开范围仍由引用该资产的业务域决定。 */
@Service
public class AssetQueryServiceImpl implements AssetQueryService {
    private final MediaAssetMapper mediaAssets;

    public AssetQueryServiceImpl(MediaAssetMapper mediaAssets) {
        this.mediaAssets = mediaAssets;
    }

    @Override
    public boolean isReady(Long assetId, AssetType expectedType) {
        if (assetId == null) {
            return false;
        }
        MediaAssetDO asset = mediaAssets.selectById(assetId);
        return asset != null && asset.getStatus() == MediaStatus.READY.code()
            && asset.getAssetType() == databaseCode(expectedType);
    }

    private int databaseCode(AssetType type) {
        return switch (type) {
            case IMAGE -> MediaAssetType.IMAGE.code();
            case DOCUMENT -> MediaAssetType.DOCUMENT.code();
        };
    }

    @Override
    public boolean isReadyImageForPurpose(Long assetId, ImageUploadPurpose purpose) {
        if (assetId == null || assetId < 1 || purpose == null) return false;
        MediaAssetDO asset = mediaAssets.selectById(assetId);
        return asset != null && asset.getStatus() == MediaStatus.READY.code()
            && asset.getAssetType() == MediaAssetType.IMAGE.code() && asset.getStorageKey() != null
            && asset.getStorageKey().startsWith(purpose.prefix() + "/");
    }
}
