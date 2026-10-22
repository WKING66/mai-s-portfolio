package dev.amai.portfolio.asset.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.AssetMetadataData;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import dev.amai.portfolio.asset.convert.AssetConvert;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Long, AssetMetadataData> lockReady(Set<Long> assetIds) {
        if (assetIds.isEmpty()) {
            return Map.of();
        }
        var assets = mediaAssetMapper.selectList(Wrappers.<MediaAssetDO>lambdaQuery()
                .in(MediaAssetDO::getId, assetIds)
                .orderByAsc(MediaAssetDO::getId).last("FOR UPDATE"));
        if (assets.size() != assetIds.size()
                || assets.stream()
                        .anyMatch(asset -> asset.getStatus() != MediaStatus.READY.code())) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "引用资源不存在或尚未就绪，请重新上传");
        }
        return assets.stream()
                .collect(Collectors.toMap(MediaAssetDO::getId, AssetConvert.MAPPER::to));
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
        return Optional.of(AssetConvert.MAPPER.to(asset));
    }

    @Override
    public Optional<AssetResourceData> findReady(Long assetId, AssetType expectedType) {
        if (assetId == null || assetId < 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(findReady(Set.of(assetId), expectedType).get(assetId));
    }

    @Override
    public Map<Long, AssetResourceData> findReady(Set<Long> assetIds) {
        return readyResources(assetIds, null);
    }

    @Override
    public Map<Long, AssetResourceData> findReady(Set<Long> assetIds, AssetType expectedType) {
        if (expectedType == null) {
            return Map.of();
        }
        return readyResources(assetIds, expectedType);
    }

    private Map<Long, AssetResourceData> readyResources(Set<Long> assetIds, AssetType expectedType) {
        // 1. 没有文件引用就不读库；图片和附件可以共用一批查询，但每条仍按实际类型签名。
        if (assetIds == null || assetIds.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids =
                assetIds.stream()
                        .filter(id -> id != null && id > 0)
                        .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        // 2. 一次读取匹配类型的 READY 资产，业务是否展示由引用方决定。
        var mediaAssets =
                mediaAssetMapper.selectList(
                        Wrappers.<MediaAssetDO>lambdaQuery()
                                .in(MediaAssetDO::getId, ids)
                                .eq(MediaAssetDO::getStatus, MediaStatus.READY.code())
                                .eq(expectedType != null, MediaAssetDO::getType,
                                        expectedType == null ? null : MediaAssetType.from(expectedType).code()));
        var resourcesById = new HashMap<Long, AssetResourceData>();
        for (MediaAssetDO asset : mediaAssets) {
            if (asset.getStorageKey() == null
                    || asset.getStorageKey().isBlank()
                    || asset.getUsage() == null
                    || asset.getContentType() == null
                    || asset.getByteSize() == null
                    || asset.getByteSize() < 1) {
                continue;
            }
            StoredObjectAccessUrl storedObjectAccessUrl;
            try {
                // 3. 图片直接展示；文档与普通文件按原始文件名下载，不能用内部 .bin 对象键命名。
                // 文件名来自资产元数据，旧附件也适用；只签名响应头，不读取或重新上传对象字节。
                storedObjectAccessUrl = asset.getType() == MediaAssetType.IMAGE.code()
                        ? objectStorage.accessUrl(asset.getStorageKey())
                        : objectStorage.downloadUrl(asset.getStorageKey(), asset.getOriginalFilename());
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
                    AssetConvert.MAPPER.to(asset, storedObjectAccessUrl));
        }
        return Map.copyOf(resourcesById);
    }
}
