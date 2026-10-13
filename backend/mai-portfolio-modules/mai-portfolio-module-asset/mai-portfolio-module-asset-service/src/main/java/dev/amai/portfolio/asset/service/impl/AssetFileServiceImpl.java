package dev.amai.portfolio.asset.service.impl;

import static dev.amai.portfolio.asset.constant.AssetPolicy.MAX_FILENAME_LENGTH;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.asset.component.ImageContentValidator;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.enums.MediaStatus;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.web.exception.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;

import java.util.Set;

/** 文档导入等服务端调用复用统一文件校验、上传、读取及失败补偿，不建立第二套 OSS 生命周期。 */
@Service
public class AssetFileServiceImpl implements AssetFileService {
    private static final Logger LOG = LoggerFactory.getLogger(AssetFileServiceImpl.class);
    private final ImageContentValidator images;
    private final AssetUploadComponent uploads;
    private final MediaAssetMapper assets;
    private final ObjectStorage storage;

    public AssetFileServiceImpl(
            ImageContentValidator images,
            AssetUploadComponent uploads,
            MediaAssetMapper assets,
            ObjectStorage storage) {
        this.images = images;
        this.uploads = uploads;
        this.assets = assets;
        this.storage = storage;
    }

    @Override
    public long upload(
            AssetUsage usage, long uploader, String filename, String contentType, byte[] bytes) {
        if (usage == null || bytes == null || bytes.length == 0 || bytes.length > usage.maxBytes())
            throw invalid();
        AssetUploadRequest request;
        if (usage.assetType() == AssetType.IMAGE) {
            var image = images.inspect(bytes, contentType, usage);
            request =
                    new AssetUploadRequest(
                            usage,
                            uploader,
                            "image." + image.extension(),
                            image.mime(),
                            image.bytes(),
                            image.width(),
                            image.height());
        } else {
            String name = filename == null ? "attachment" : filename.replace('\\', '/');
            name = name.substring(name.lastIndexOf('/') + 1).strip();
            if (name.isEmpty()
                    || name.length() > MAX_FILENAME_LENGTH
                    || name.codePoints().anyMatch(Character::isISOControl)) throw invalid();
            request =
                    new AssetUploadRequest(
                            usage, uploader, name, MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes, null, null);
        }
        return uploads.upload(request);
    }

    @Override
    /** 供已完成业务授权的导出调用；调用方必须关闭返回流，普通展示只使用 AssetQueryService。 */
    public AssetFileContent openReady(long id) {
        var asset = assets.selectById(id);
        if (asset == null || asset.getStatus() != MediaStatus.READY.code())
            throw new ApiException(ApiErrorCode.NOT_FOUND, "资源不存在或尚未就绪");
        try {
            var content = storage.open(asset.getStorageKey());
            return new AssetFileContent(
                    content.inputStream(),
                    content.byteSize(),
                    asset.getOriginalFilename(),
                    asset.getMimeType());
        } catch (RuntimeException failure) {
            LOG.warn(
                    "Asset read failed, assetId={}, failureType={}",
                    id,
                    failure.getClass().getSimpleName());
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "资源读取失败，请重试");
        }
    }

    @Override
    public void discardUnbound(Set<Long> ids, long uploader) {
        for (long id : ids) {
            try {
                var asset = assets.selectById(id);
                if (asset == null
                        || !java.util.Objects.equals(asset.getUploaderAccountId(), uploader))
                    continue;
                // 先删除数据库记录；任何已绑定资源会被 FK 拒绝，不能先删除 OSS。
                if (assets.delete(
                                Wrappers.<MediaAssetDO>lambdaQuery()
                                        .eq(MediaAssetDO::getId, id)
                                        .eq(MediaAssetDO::getUploaderAccountId, uploader))
                        == 1) storage.delete(asset.getStorageKey());
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Unbound asset cleanup failed, assetId={}, failureType={}",
                        id,
                        failure.getClass().getSimpleName());
            }
        }
    }

    private ApiException invalid() {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, "文件为空、过大或元数据无效");
    }
}
