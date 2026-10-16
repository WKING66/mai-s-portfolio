package dev.amai.portfolio.asset.component;

import static dev.amai.portfolio.asset.constant.AssetPolicy.*;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUploadComponent;
import dev.amai.portfolio.asset.api.AssetUploadRequest;
import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.enums.MediaAssetType;
import dev.amai.portfolio.asset.enums.MediaStatus;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 通用资产生命周期：PENDING → OSS → READY；失败尽力记录 FAILED 并明确报错。
 * 元数据短事务与外部上传分离。业务绑定失败不删除新对象，更不自动删除旧对象。
 * 此组件不接受 HTTP、不抓取网络、不判定文档可见性，也不替代各用途的内容校验。
 */
@Component
public class AssetUploadComponentImpl implements AssetUploadComponent {
    private static final Logger LOG = LoggerFactory.getLogger(AssetUploadComponentImpl.class);
    private static final int EDITOR_UPLOAD_SOURCE = 2;
    private static final Pattern MIME = Pattern.compile("[a-z0-9.+-]+/[a-z0-9.+-]+");
    private final MediaAssetMapper mediaAssetMapper;
    private final ObjectStorage objectStorage;
    private final TransactionTemplate transactionTemplate;

    public AssetUploadComponentImpl(
            MediaAssetMapper mediaAssetMapper,
            ObjectStorage objectStorage,
            PlatformTransactionManager transactionManager) {
        this.mediaAssetMapper = mediaAssetMapper;
        this.objectStorage = objectStorage;
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public long upload(AssetUploadRequest request) {
        // 1. 校验内部命令并保存内容快照；实际内容策略由用途校验入口负责。
        validate(request);
        // 独立快照保证哈希、大小和实际上传内容一致，不受调用方并发修改数组影响。
        byte[] bytes = request.content().clone();
        String extension =
                request.usage().assetType() == AssetType.IMAGE
                        ? request.filename().substring(request.filename().lastIndexOf('.'))
                        : ".bin";
        String key = request.usage().objectPrefix() + "/" + UUID.randomUUID() + extension;
        MediaAssetDO asset = pendingAsset(request, bytes, key);
        // 2. 独立短事务持久化 PENDING；外层事务暂停，OSS 上传不持有数据库事务。
        transactionTemplate.executeWithoutResult(
                status -> {
                    if (mediaAssetMapper.insert(asset) != 1 || asset.getId() == null) {
                        throw failed();
                    }
                });
        try {
            // 3. 上传后在新事务条件更新 READY；存储异常和 READY 更新异常均进入失败分支。
            objectStorage.store(
                    new ObjectStorageWriteRequest(
                            key,
                            new ByteArrayInputStream(bytes),
                            bytes.length,
                            request.contentType()));
            transactionTemplate.executeWithoutResult(
                    status -> updateState(asset.getId(), MediaStatus.READY));
            return asset.getId();
        } catch (RuntimeException failure) {
            // 4. 尽力把仍处于 PENDING 的记录标记 FAILED；二次失败保留 suppressed 因果。
            // 不在这里删除已上传对象，避免误删；绑定失败/补偿时机由调用业务决定。
            try {
                transactionTemplate.executeWithoutResult(
                        status -> updateState(asset.getId(), MediaStatus.FAILED));
            } catch (RuntimeException metadataFailure) {
                failure.addSuppressed(metadataFailure);
                LOG.error("Asset failure state could not be persisted, assetId={}", asset.getId());
            }
            // 不记录厂商异常文本、对象地址或签名；保留因果链供受控调试。
            LOG.warn(
                    "Asset upload failed, assetId={}, failureType={}",
                    asset.getId(),
                    failure.getClass().getSimpleName());
            ApiException translated = failed();
            translated.initCause(failure);
            throw translated;
        }
    }

    /** 校验命令结构及持久化容量，不再次解码图片，也不判断业务对象可见性。 */
    private void validate(AssetUploadRequest request) {
        if (request == null
                || request.usage() == null
                || request.uploaderAccountId() < 1
                || request.content() == null
                || request.content().length == 0
                || request.content().length > request.usage().maxBytes()
                || request.filename() == null
                || request.filename().isBlank()
                || request.filename().length() > MAX_FILENAME_LENGTH
                || request.filename().codePoints().anyMatch(Character::isISOControl)
                || request.contentType() == null
                || request.contentType().length() > MAX_CONTENT_TYPE_LENGTH
                || !MIME.matcher(request.contentType()).matches()
                || (request.usage().assetType() == AssetType.IMAGE
                        && (request.width() == null
                                || request.height() == null
                                || request.width() < 1
                                || request.height() < 1
                                || !(request.filename().endsWith(".png")
                                        || request.filename().endsWith(".jpg"))))
                || (request.usage().assetType() != AssetType.IMAGE
                        && (request.width() != null || request.height() != null))) {
            throw new ApiException(
                    ApiErrorCode.VALIDATION_FAILED, AssetMessageConstants.UPLOAD_INVALID);
        }
    }

    private MediaAssetDO pendingAsset(AssetUploadRequest request, byte[] bytes, String key) {
        MediaAssetDO asset = new MediaAssetDO();
        asset.setType(MediaAssetType.from(request.usage().assetType()).code());
        asset.setUsage(request.usage().name());
        asset.setUploaderAccountId(request.uploaderAccountId());
        asset.setSourceType(EDITOR_UPLOAD_SOURCE);
        asset.setStorageKey(key);
        asset.setOriginalFilename(request.filename());
        asset.setContentType(request.contentType());
        asset.setByteSize((long) bytes.length);
        asset.setWidth(request.width());
        asset.setHeight(request.height());
        asset.setSha256(sha256(bytes));
        asset.setStatus(MediaStatus.PENDING.code());
        asset.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        asset.setUpdatedAt(asset.getCreatedAt());
        return asset;
    }

    private void updateState(long id, MediaStatus state) {
        if (mediaAssetMapper.update(
                        null,
                        Wrappers.<MediaAssetDO>lambdaUpdate()
                                .eq(MediaAssetDO::getId, id)
                                .eq(MediaAssetDO::getStatus, MediaStatus.PENDING.code())
                                .set(MediaAssetDO::getStatus, state.code())
                                .set(MediaAssetDO::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)))
                != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, AssetMessageConstants.UPLOAD_FAILED);
        }
    }

    private byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ApiException failed() {
        return new ApiException(ApiErrorCode.INTERNAL_ERROR, AssetMessageConstants.UPLOAD_FAILED);
    }
}
