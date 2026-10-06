package dev.amai.portfolio.asset.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.ImageAssetUploadService;
import dev.amai.portfolio.asset.config.AvatarUploadProperties;
import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.enums.MediaAssetType;
import dev.amai.portfolio.asset.enums.MediaStatus;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 新头像独立对象写入，不覆盖已有头像。PENDING/READY/FAILED 各自短事务提交，
 * OSS 请求不占数据库事务；绑定失败时保留资产记录供人工排查，绝不自动删除旧头像。
 */
@Service
public class ImageAssetUploadServiceImpl implements ImageAssetUploadService {
    private static final Logger LOG = LoggerFactory.getLogger(ImageAssetUploadServiceImpl.class);
    private static final int EDITOR_UPLOAD_SOURCE = 2;
    private static final String PNG_MIME = "image/png";
    private static final String JPEG_MIME = "image/jpeg";
    private final MediaAssetMapper assets;
    private final ObjectStorage storage;
    private final AvatarUploadProperties limits;
    private final TransactionTemplate transactions;

    public ImageAssetUploadServiceImpl(MediaAssetMapper assets, ObjectStorage storage,
            AvatarUploadProperties limits, PlatformTransactionManager transactionManager) {
        this.assets = assets;
        this.storage = storage;
        this.limits = limits;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public int maxAvatarBytes() {
        return limits.maxBytes();
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public long uploadAvatar(byte[] content, String declaredContentType) {
        CheckedImage image = inspect(content, declaredContentType);
        String key = "account/avatars/" + UUID.randomUUID() + "." + image.extension();
        MediaAssetDO asset = pendingAsset(image, key);
        transactions.executeWithoutResult(status -> {
            if (assets.insert(asset) != 1 || asset.getId() == null) {
                throw new ApiException(ApiErrorCode.INTERNAL_ERROR, AssetMessageConstants.AVATAR_UPLOAD_FAILED);
            }
        });
        try {
            // 图片解码后重新编码，仅上传像素内容，移除 EXIF、附带脚本和多余元数据。
            storage.store(new ObjectStorageWriteRequest(key,
                new ByteArrayInputStream(image.bytes()), image.bytes().length, image.mime()));
            transactions.executeWithoutResult(status -> updateState(asset.getId(), MediaStatus.READY));
            return asset.getId();
        } catch (RuntimeException failure) {
            try {
                transactions.executeWithoutResult(status -> updateState(asset.getId(), MediaStatus.FAILED));
            } catch (RuntimeException metadataFailure) {
                failure.addSuppressed(metadataFailure);
                LOG.error("Avatar failure state could not be persisted, assetId={}", asset.getId());
            }
            // 不记录上游异常 message，它可能包含私有 OSS 地址或签名。
            LOG.warn("Avatar upload failed, assetId={}, failureType={}",
                asset.getId(), failure.getClass().getSimpleName());
            ApiException translated = new ApiException(ApiErrorCode.INTERNAL_ERROR,
                AssetMessageConstants.AVATAR_UPLOAD_FAILED);
            // 保留因果链供受控调试；现有业务异常处理与 @ApiLog 都不记录 cause 的敏感文本。
            translated.initCause(failure);
            throw translated;
        }
    }

    private CheckedImage inspect(byte[] content, String declaredContentType) {
        if (content == null || content.length == 0) {
            throw invalid(AssetMessageConstants.AVATAR_EMPTY);
        }
        if (content.length > limits.maxBytes()) {
            throw invalid(AssetMessageConstants.AVATAR_TOO_LARGE);
        }
        if (!PNG_MIME.equals(declaredContentType) && !JPEG_MIME.equals(declaredContentType)) {
            throw invalid(AssetMessageConstants.AVATAR_TYPE_INVALID);
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid(AssetMessageConstants.AVATAR_TYPE_INVALID);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String mime = "png".equals(format) ? PNG_MIME : "jpeg".equals(format) ? JPEG_MIME : "";
                if (!mime.equals(declaredContentType)) {
                    throw invalid(AssetMessageConstants.AVATAR_TYPE_INVALID);
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > limits.maxDimension() || height > limits.maxDimension()) {
                    throw invalid(AssetMessageConstants.AVATAR_DIMENSIONS_INVALID);
                }
                BufferedImage pixels = reader.read(0);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (pixels == null || !ImageIO.write(pixels, format, output)) {
                    throw invalid(AssetMessageConstants.AVATAR_TYPE_INVALID);
                }
                byte[] bytes = output.toByteArray();
                // 重编码后也检查上限；不能用小压缩文件绕过存储大小策略。
                if (bytes.length > limits.maxBytes()) {
                    throw invalid(AssetMessageConstants.AVATAR_TOO_LARGE);
                }
                return new CheckedImage(bytes, mime, PNG_MIME.equals(mime) ? "png" : "jpg", width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException invalidImage) {
            throw invalid(AssetMessageConstants.AVATAR_TYPE_INVALID);
        }
    }

    private MediaAssetDO pendingAsset(CheckedImage image, String key) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MediaAssetDO asset = new MediaAssetDO();
        asset.setAssetType(MediaAssetType.IMAGE.code());
        asset.setSourceType(EDITOR_UPLOAD_SOURCE);
        asset.setStorageKey(key);
        // 不存用户原始文件名，避免敏感路径或文件名进入资产日志/接口。
        asset.setOriginalFilename("avatar." + image.extension());
        asset.setMimeType(image.mime());
        asset.setByteSize((long) image.bytes().length);
        asset.setWidth(image.width());
        asset.setHeight(image.height());
        asset.setSha256(sha256(image.bytes()));
        asset.setStatus(MediaStatus.PENDING.code());
        asset.setCreatedAt(now);
        asset.setUpdatedAt(now);
        return asset;
    }

    private void updateState(long assetId, MediaStatus state) {
        int changed = assets.update(null, Wrappers.<MediaAssetDO>lambdaUpdate()
            .eq(MediaAssetDO::getId, assetId)
            .eq(MediaAssetDO::getStatus, MediaStatus.PENDING.code())
            .set(MediaAssetDO::getStatus, state.code())
            .set(MediaAssetDO::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (changed != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, AssetMessageConstants.AVATAR_UPLOAD_FAILED);
        }
    }

    private byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }

    private record CheckedImage(byte[] bytes, String mime, String extension, int width, int height) {
    }
}
