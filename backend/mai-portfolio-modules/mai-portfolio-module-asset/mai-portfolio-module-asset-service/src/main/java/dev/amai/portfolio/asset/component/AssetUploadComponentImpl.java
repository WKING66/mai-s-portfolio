package dev.amai.portfolio.asset.component;

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
    private static final int MAX_BUFFERED_BYTES = 64 * 1024 * 1024;
    private static final Pattern PREFIX = Pattern.compile("[a-z0-9-]+(?:/[a-z0-9-]+)*");
    private static final Pattern FILENAME = Pattern.compile("[a-z0-9-]+\\.[a-z0-9]{1,10}");
    private static final Pattern MIME = Pattern.compile("[a-z0-9.+-]+/[a-z0-9.+-]+");
    private final MediaAssetMapper assets;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;

    public AssetUploadComponentImpl(MediaAssetMapper assets, ObjectStorage storage,
            PlatformTransactionManager transactionManager) {
        this.assets = assets;
        this.storage = storage;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public long upload(AssetUploadRequest request) {
        validate(request);
        // 独立快照保证哈希、大小和实际上传内容一致，不受调用方并发修改数组影响。
        byte[] bytes = request.content().clone();
        String extension = request.filename().substring(request.filename().lastIndexOf('.'));
        String key = request.prefix() + "/" + UUID.randomUUID() + extension;
        MediaAssetDO asset = pendingAsset(request, bytes, key);
        transactions.executeWithoutResult(status -> {
            if (assets.insert(asset) != 1 || asset.getId() == null) throw failed();
        });
        try {
            storage.store(new ObjectStorageWriteRequest(key, new ByteArrayInputStream(bytes),
                bytes.length, request.contentType()));
            transactions.executeWithoutResult(status -> updateState(asset.getId(), MediaStatus.READY));
            return asset.getId();
        } catch (RuntimeException failure) {
            try {
                transactions.executeWithoutResult(status -> updateState(asset.getId(), MediaStatus.FAILED));
            } catch (RuntimeException metadataFailure) {
                failure.addSuppressed(metadataFailure);
                LOG.error("Asset failure state could not be persisted, assetId={}", asset.getId());
            }
            // 不记录厂商异常文本、对象地址或签名；保留因果链供受控调试。
            LOG.warn("Asset upload failed, assetId={}, failureType={}",
                asset.getId(), failure.getClass().getSimpleName());
            ApiException translated = failed();
            translated.initCause(failure);
            throw translated;
        }
    }

    private void validate(AssetUploadRequest request) {
        if (request == null || request.type() == null || request.content() == null
                || request.content().length == 0 || request.content().length > MAX_BUFFERED_BYTES
                || request.prefix() == null || request.prefix().length() > 120 || !PREFIX.matcher(request.prefix()).matches()
                || request.filename() == null || request.filename().length() > 100 || !FILENAME.matcher(request.filename()).matches()
                || request.contentType() == null || request.contentType().length() > 120 || !MIME.matcher(request.contentType()).matches()
                || (request.type() == AssetType.IMAGE && (request.width() == null || request.height() == null
                    || request.width() < 1 || request.height() < 1))
                || (request.type() == AssetType.DOCUMENT && (request.width() != null || request.height() != null))) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, AssetMessageConstants.UPLOAD_INVALID);
        }
    }

    private MediaAssetDO pendingAsset(AssetUploadRequest request, byte[] bytes, String key) {
        MediaAssetDO asset = new MediaAssetDO();
        asset.setAssetType(request.type() == AssetType.IMAGE ? MediaAssetType.IMAGE.code() : MediaAssetType.DOCUMENT.code());
        asset.setSourceType(EDITOR_UPLOAD_SOURCE);
        asset.setStorageKey(key);
        asset.setOriginalFilename(request.filename());
        asset.setMimeType(request.contentType());
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
        if (assets.update(null, Wrappers.<MediaAssetDO>lambdaUpdate()
                .eq(MediaAssetDO::getId, id).eq(MediaAssetDO::getStatus, MediaStatus.PENDING.code())
                .set(MediaAssetDO::getStatus, state.code())
                .set(MediaAssetDO::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC))) != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, AssetMessageConstants.UPLOAD_FAILED);
        }
    }

    private byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private ApiException failed() { return new ApiException(ApiErrorCode.INTERNAL_ERROR, AssetMessageConstants.UPLOAD_FAILED); }
}
