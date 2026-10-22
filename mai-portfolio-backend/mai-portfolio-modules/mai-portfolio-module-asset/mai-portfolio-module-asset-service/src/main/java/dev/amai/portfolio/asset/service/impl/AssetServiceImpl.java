package dev.amai.portfolio.asset.service.impl;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.component.ImageContentValidator;
import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import dev.amai.portfolio.asset.convert.AssetConvert;
import dev.amai.portfolio.asset.entity.vo.AssetVo;
import dev.amai.portfolio.asset.service.AssetService;
import dev.amai.portfolio.system.api.AccountIdentityService;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.IOException;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 一套文件接收、内容校验与资产上传编排；用途差异不复制 Service。 */
@Service
public class AssetServiceImpl implements AssetService {
    private final ImageContentValidator imageContentValidator;
    private final AssetFileService assetFileService;
    private final AssetQueryService assetQueryService;
    private final AccountIdentityService accountIdentityService;

    public AssetServiceImpl(
            ImageContentValidator imageContentValidator,
            AssetFileService assetFileService,
            AssetQueryService assetQueryService,
            AccountIdentityService accountIdentityService) {
        this.imageContentValidator = imageContentValidator;
        this.assetFileService = assetFileService;
        this.assetQueryService = assetQueryService;
        this.accountIdentityService = accountIdentityService;
    }

    @Override
    public AssetVo resource(Long assetId) {
        // 1. 从认证域取得有效账号，资产入口不自行读取会话或接受客户端身份。
        long accountId = accountIdentityService.currentAccountId();
        var metadata =
                assetQueryService
                        .findReadyMetadata(assetId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ApiErrorCode.NOT_FOUND,
                                                AssetMessageConstants.ASSET_NOT_FOUND));
        // 查询接口只允许上传者或 OWNER；公开引用地址从公开业务投影获取。
        if (!Objects.equals(metadata.uploaderAccountId(), accountId)
                && !accountIdentityService.currentRoles().contains(AuthConstants.OWNER_ROLE)) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, "无权访问他人上传的资产");
        }
        var resource =
                assetQueryService
                        .findReady(assetId, metadata.type())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ApiErrorCode.NOT_FOUND,
                                                AssetMessageConstants.ASSET_NOT_FOUND));
        return AssetConvert.MAPPER.to(resource);
    }

    @Override
    public AssetVo upload(MultipartFile file, AssetUsage usage) {
        // 1. 从认证域取得有效账号，资产入口不自行读取会话或接受客户端身份。
        long accountId = accountIdentityService.currentAccountId();
        if (usage == null) {
            throw invalid(AssetMessageConstants.UPLOAD_INVALID);
        }
        // 2. 先按用途校验声明大小，再有界读取，多读一字节识别不可靠的长度声明。
        if (file == null || file.isEmpty()) {
            throw invalid(AssetMessageConstants.FILE_EMPTY);
        }
        int limit =
                usage.assetType() == AssetType.IMAGE
                        ? imageContentValidator.maxBytes(usage)
                        : usage.maxBytes();
        if (file.getSize() > limit) {
            throw invalid(AssetMessageConstants.FILE_TOO_LARGE);
        }
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes(limit + 1);
        } catch (IOException failure) {
            throw invalid(AssetMessageConstants.UPLOAD_INVALID);
        }
        if (bytes.length == 0) {
            throw invalid(AssetMessageConstants.FILE_EMPTY);
        }
        if (bytes.length > limit) {
            throw invalid(AssetMessageConstants.FILE_TOO_LARGE);
        }
        // 3. 复用文件校验与唯一上传生命周期；成功后查询 READY 元数据及签名地址。
        long id =
                assetFileService.upload(
                        usage, accountId, file.getOriginalFilename(), file.getContentType(), bytes);
        var resource =
                assetQueryService
                        .findReady(id, usage.assetType())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                ApiErrorCode.INTERNAL_ERROR,
                                                AssetMessageConstants.UPLOAD_FAILED));
        return AssetConvert.MAPPER.to(resource);
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
