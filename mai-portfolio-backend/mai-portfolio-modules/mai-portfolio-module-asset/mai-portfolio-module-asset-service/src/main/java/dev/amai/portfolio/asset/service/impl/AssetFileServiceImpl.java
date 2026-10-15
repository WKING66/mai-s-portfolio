package dev.amai.portfolio.asset.service.impl;

import static dev.amai.portfolio.asset.constant.AssetPolicy.MAX_FILENAME_LENGTH;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.asset.component.ImageContentValidator;
import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.enums.MediaStatus;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.web.exception.*;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

/** 文档导入等服务端调用复用统一文件校验、上传、读取及失败补偿，不建立第二套 OSS 生命周期。 */
@Service
public class AssetFileServiceImpl implements AssetFileService {
    private static final Logger LOG = LoggerFactory.getLogger(AssetFileServiceImpl.class);
    private final ImageContentValidator imageContentValidator;
    private final AssetUploadComponent assetUploadComponent;
    private final MediaAssetMapper mediaAssetMapper;
    private final ObjectStorage objectStorage;

    public AssetFileServiceImpl(
            ImageContentValidator imageContentValidator,
            AssetUploadComponent assetUploadComponent,
            MediaAssetMapper mediaAssetMapper,
            ObjectStorage objectStorage) {
        this.imageContentValidator = imageContentValidator;
        this.assetUploadComponent = assetUploadComponent;
        this.mediaAssetMapper = mediaAssetMapper;
        this.objectStorage = objectStorage;
    }

    @Override
    public long upload(
            AssetUsage usage, long uploader, String filename, String contentType, byte[] bytes) {
        // 1. 校验用途与字节上限；内存命令不从路径推断权限或文件用途。
        if (usage == null
                || bytes == null
                || bytes.length == 0
                || bytes.length > usage.maxBytes()) {
            throw invalid();
        }
        AssetUploadRequest request;
        // 2. 图片解码重编码；附件只保留安全文件名，使用通用下载 MIME。
        if (usage.assetType() == AssetType.IMAGE) {
            var image = imageContentValidator.inspect(bytes, contentType, usage);
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
                    || name.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid();
            }
            request =
                    new AssetUploadRequest(
                            usage,
                            uploader,
                            name,
                            MediaType.APPLICATION_OCTET_STREAM_VALUE,
                            bytes,
                            null,
                            null);
        }
        // 3. 所有用途复用同一 PENDING/READY 生命周期，不各自实现 OSS 上传。
        return assetUploadComponent.upload(request);
    }

    /** 供已完成业务授权的导出调用；调用方必须关闭返回流，普通展示只使用 AssetQueryService。 */
    @Override
    public AssetFileContent openReady(long id) {
        var asset = mediaAssetMapper.selectById(id);
        if (asset == null || asset.getStatus() != MediaStatus.READY.code()) {
            throw new ApiException(
                    ApiErrorCode.NOT_FOUND, AssetMessageConstants.FILE_CONTENT_NOT_FOUND);
        }
        try {
            var content = objectStorage.open(asset.getStorageKey());
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
            throw new ApiException(
                    ApiErrorCode.INTERNAL_ERROR, AssetMessageConstants.FILE_READ_FAILED);
        }
    }

    @Override
    public void discardUnbound(Set<Long> ids, long uploader) {
        // 逐项尽力清理，单项失败只记安全摘要，不扩大为整个导入补偿失败。
        for (long id : ids) {
            try {
                var asset = mediaAssetMapper.selectById(id);
                if (asset == null
                        || !java.util.Objects.equals(asset.getUploaderAccountId(), uploader)) {
                    continue;
                }
                // 先删除数据库记录；任何已绑定资源会被 FK 拒绝，不能先删除 OSS。
                if (mediaAssetMapper.delete(
                                Wrappers.<MediaAssetDO>lambdaQuery()
                                        .eq(MediaAssetDO::getId, id)
                                        .eq(MediaAssetDO::getUploaderAccountId, uploader))
                        == 1) {
                    // 数据库删除成功后才删除对象；OSS 失败可能留下孤儿对象，不恢复元数据。
                    objectStorage.delete(asset.getStorageKey());
                }
            } catch (RuntimeException failure) {
                LOG.warn(
                        "Unbound asset cleanup failed, assetId={}, failureType={}",
                        id,
                        failure.getClass().getSimpleName());
            }
        }
    }

    private ApiException invalid() {
        return new ApiException(
                ApiErrorCode.VALIDATION_FAILED, AssetMessageConstants.FILE_METADATA_INVALID);
    }
}
