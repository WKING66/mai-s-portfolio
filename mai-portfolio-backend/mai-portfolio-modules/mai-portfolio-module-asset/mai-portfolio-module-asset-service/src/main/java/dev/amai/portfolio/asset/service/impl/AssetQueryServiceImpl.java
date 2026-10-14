package dev.amai.portfolio.asset.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.AssetMetadataData;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.enums.MediaAssetType;
import dev.amai.portfolio.asset.enums.MediaStatus;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.StoredObjectAccessUrl;
import dev.amai.portfolio.storage.exception.ObjectStorageException;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 元数据与位置归资产域，是否展示该引用归业务域；不从路径反推用途。 */
@Service
public class AssetQueryServiceImpl implements AssetQueryService {
    private static final Logger LOG = LoggerFactory.getLogger(AssetQueryServiceImpl.class);
    private final MediaAssetMapper mediaAssetMapper;
    private final ObjectStorage objectStorage;

    public AssetQueryServiceImpl(MediaAssetMapper mediaAssetMapper, ObjectStorage objectStorage) {
        this.mediaAssetMapper = mediaAssetMapper;
        this.objectStorage = objectStorage;
    }

    @Override
    public Optional<AssetMetadataData> findReadyMetadata(Long assetId) {
        if (assetId == null || assetId < 1) {
            return Optional.empty();
        }
        MediaAssetDO asset = mediaAssetMapper.selectById(assetId);
        if (asset == null
                || asset.getStatus() == null
                || asset.getStatus() != MediaStatus.READY.code()) {
            return Optional.empty();
        }
        return Optional.of(
                new AssetMetadataData(
                        asset.getId(),
                        MediaAssetType.toApiType(asset.getAssetType()),
                        AssetUsage.valueOf(asset.getUsage()),
                        asset.getUploaderAccountId()));
    }

    @Override
    public Optional<AssetResourceData> findReady(Long assetId, AssetType expectedType) {
        if (assetId == null || assetId < 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(findReady(Set.of(assetId), expectedType).get(assetId));
    }

    @Override
    public Map<Long, AssetResourceData> findReady(Set<Long> assetIds, AssetType expectedType) {
        // 1. 规范化查询集合；无有效身份或类型时不访问数据库与存储。
        if (assetIds == null || assetIds.isEmpty() || expectedType == null) {
            return Map.of();
        }
        Set<Long> ids =
                assetIds.stream().filter(id -> id != null && id > 0).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        // 2. 一次读取匹配类型的 READY 资产，业务是否展示由引用方决定。
        var mediaAssets =
                mediaAssetMapper.selectList(
                        Wrappers.<MediaAssetDO>lambdaQuery()
                                .in(MediaAssetDO::getId, ids)
                                .eq(MediaAssetDO::getStatus, MediaStatus.READY.code())
                                .eq(
                                        MediaAssetDO::getAssetType,
                                        MediaAssetType.from(expectedType).code()));
        var resourcesById = new HashMap<Long, AssetResourceData>();
        for (MediaAssetDO asset : mediaAssets) {
            if (asset.getStorageKey() == null
                    || asset.getStorageKey().isBlank()
                    || asset.getUsage() == null
                    || asset.getMimeType() == null
                    || asset.getByteSize() == null
                    || asset.getByteSize() < 1) {
                continue;
            }
            StoredObjectAccessUrl storedObjectAccessUrl;
            try {
                // 3. 每个有效资产生成独立签名，不读取对象字节；任一生成失败按既有契约报错。
                storedObjectAccessUrl = objectStorage.accessUrl(asset.getStorageKey());
            } catch (ObjectStorageException error) {
                LOG.warn(
                        "Asset address generation failed, assetId={}, failureType={}",
                        asset.getId(),
                        error.getClass().getSimpleName());
                var translated =
                        new ApiException(
                                ApiErrorCode.INTERNAL_ERROR,
                                AssetMessageConstants.RESOURCE_ACCESS_FAILED);
                translated.initCause(error);
                throw translated;
            }
            resourcesById.put(
                    asset.getId(),
                    new AssetResourceData(
                            asset.getId(),
                            expectedType,
                            AssetUsage.valueOf(asset.getUsage()),
                            asset.getUploaderAccountId(),
                            asset.getOriginalFilename(),
                            asset.getMimeType(),
                            asset.getByteSize(),
                            storedObjectAccessUrl.url(),
                            storedObjectAccessUrl.expiresAt()));
        }
        return Map.copyOf(resourcesById);
    }
}
