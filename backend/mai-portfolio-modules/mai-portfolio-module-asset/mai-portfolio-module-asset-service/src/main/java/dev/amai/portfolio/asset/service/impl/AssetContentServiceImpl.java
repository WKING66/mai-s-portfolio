package dev.amai.portfolio.asset.service.impl;

import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetContentService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.enums.MediaAssetType;
import dev.amai.portfolio.asset.enums.MediaStatus;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.StoredObjectContent;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 从私有对象存储读取 READY 资产，不在资产域内擅自授予公开访问权限。 */
@Service
public class AssetContentServiceImpl implements AssetContentService {
    private final MediaAssetMapper mediaAssets;
    private final ObjectStorage objectStorage;

    public AssetContentServiceImpl(MediaAssetMapper mediaAssets, ObjectStorage objectStorage) {
        this.mediaAssets = mediaAssets;
        this.objectStorage = objectStorage;
    }

    @Override
    public Optional<AssetContentData> openReady(Long assetId, AssetType expectedType) {
        if (assetId == null) {
            return Optional.empty();
        }
        MediaAssetDO asset = mediaAssets.selectById(assetId);
        if (asset == null || asset.getStatus() != MediaStatus.READY.code()
                || asset.getAssetType() != databaseCode(expectedType)) {
            return Optional.empty();
        }
        StoredObjectContent content = objectStorage.open(asset.getStorageKey());
        return Optional.of(new AssetContentData(content.inputStream(), content.byteSize(),
            asset.getMimeType(), asset.getOriginalFilename(), expectedType));
    }

    private int databaseCode(AssetType type) {
        return switch (type) {
            case IMAGE -> MediaAssetType.IMAGE.code();
            case DOCUMENT -> MediaAssetType.DOCUMENT.code();
        };
    }
}
