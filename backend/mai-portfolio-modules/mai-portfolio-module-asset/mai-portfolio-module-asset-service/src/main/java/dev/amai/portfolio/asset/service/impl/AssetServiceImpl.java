package dev.amai.portfolio.asset.service.impl;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.component.ImageContentValidator;
import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import dev.amai.portfolio.asset.entity.vo.AssetVo;
import dev.amai.portfolio.asset.service.AssetService;
import dev.amai.portfolio.system.api.AccountIdentityService;
import dev.amai.portfolio.system.api.OwnerAuthorizationService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Objects;

/** 一套文件接收、内容校验与资产上传编排；用途差异不复制 Service。 */
@Service
public class AssetServiceImpl implements AssetService {
    private final ImageContentValidator images;
    private final AssetFileService files;
    private final AssetQueryService assets;
    private final AccountIdentityService accounts;
    private final OwnerAuthorizationService authorization;

    public AssetServiceImpl(
            ImageContentValidator images,
            AssetFileService files,
            AssetQueryService assets,
            AccountIdentityService accounts,
            OwnerAuthorizationService authorization) {
        this.images = images;
        this.files = files;
        this.assets = assets;
        this.accounts = accounts;
        this.authorization = authorization;
    }

    @Override
    public AssetVo resource(Long assetId) {
        long accountId = accounts.currentAccountId();
        var metadata =
                assets.findReadyMetadata(assetId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ApiErrorCode.NOT_FOUND,
                                                AssetMessageConstants.ASSET_NOT_FOUND));
        // 查询接口只允许上传者或 OWNER；公开引用地址从公开业务投影获取。
        if (!Objects.equals(metadata.uploaderAccountId(), accountId))
            authorization.requireOwner();
        var resource =
                assets.findReady(assetId, metadata.type())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ApiErrorCode.NOT_FOUND,
                                                AssetMessageConstants.ASSET_NOT_FOUND));
        return toVo(resource);
    }

    @Override
    public AssetVo upload(MultipartFile file, AssetUsage usage) {
        long accountId = accounts.currentAccountId();
        if (usage == null) throw invalid(AssetMessageConstants.UPLOAD_INVALID);
        // 普通账号仅能创建本人头像资产；网站内容资源由已有 OWNER 能力授权。
        if (usage != AssetUsage.AVATAR) authorization.requireOwner();
        if (file == null || file.isEmpty()) throw invalid(AssetMessageConstants.FILE_EMPTY);
        int limit =
                usage.assetType() == AssetType.IMAGE ? images.maxBytes(usage) : usage.maxBytes();
        if (file.getSize() > limit) throw invalid(AssetMessageConstants.FILE_TOO_LARGE);
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(limit + 1);
        } catch (IOException failure) {
            throw invalid(AssetMessageConstants.UPLOAD_INVALID);
        }
        if (bytes.length == 0) throw invalid(AssetMessageConstants.FILE_EMPTY);
        if (bytes.length > limit) throw invalid(AssetMessageConstants.FILE_TOO_LARGE);
        long id =
                files.upload(
                        usage, accountId, file.getOriginalFilename(), file.getContentType(), bytes);
        var resource =
                assets.findReady(id, usage.assetType())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ApiErrorCode.INTERNAL_ERROR,
                                                AssetMessageConstants.UPLOAD_FAILED));
        return toVo(resource);
    }

    private AssetVo toVo(dev.amai.portfolio.asset.api.AssetResourceData resource) {
        return new AssetVo(
                resource.id(),
                resource.resourceUrl(),
                resource.expiresAt(),
                resource.contentType(),
                resource.byteSize(),
                resource.usage());
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
