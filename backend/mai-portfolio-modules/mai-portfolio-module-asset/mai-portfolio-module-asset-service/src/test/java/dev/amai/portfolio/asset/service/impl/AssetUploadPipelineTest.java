package dev.amai.portfolio.asset.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.asset.config.AvatarUploadProperties;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUploadRequest;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.component.AssetUploadComponentImpl;
import dev.amai.portfolio.asset.component.ImageContentValidator;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.enums.MediaStatus;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.ObjectStorageWriteRequest;
import dev.amai.portfolio.storage.StoredObject;
import dev.amai.portfolio.storage.exception.ObjectStorageException;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 验证解码前边界、重新编码、独立对象写入和可追踪失败；OSS 真联调由验收补充。 */
class AssetUploadPipelineTest {
    private final MediaAssetMapper assets = mock(MediaAssetMapper.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final TestTransactionManager manager = new TestTransactionManager();
    private final ImageContentValidator validator = new ImageContentValidator(new AvatarUploadProperties(2097152, 2048));
    private final AssetUploadComponentImpl component = new AssetUploadComponentImpl(assets, storage, manager);

    private Long upload(byte[] bytes, String mime, AssetUsage usage) {
        var image = validator.inspect(bytes, mime, usage);
        return component.upload(new AssetUploadRequest(usage, 7L, "image." + image.extension(),
            image.mime(), image.bytes(), image.width(), image.height()));
    }

    @BeforeAll
    static void initializeMybatisLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "unit-test"),
            MediaAssetDO.class);
    }

    @BeforeEach
    void arrange() {
        when(assets.insert(any(MediaAssetDO.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            var row = invocation.<MediaAssetDO>getArgument(0);
            row.setId(19L);
            assertThat(row.getStatus()).isEqualTo(MediaStatus.PENDING.code());
            return 1;
        });
        when(assets.update(isNull(), any())).thenReturn(1);
        when(storage.store(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ObjectStorageWriteRequest request = invocation.getArgument(0);
            return new StoredObject(request.objectKey(), request.byteSize(), "safe-etag");
        });
    }

    @Test
    void pngIsReencodedToUniquePrivateObjectWithoutTrailingPayload() throws Exception {
        byte[] image = image("png", 12, 15);
        byte[] content = Arrays.copyOf(image, image.length + 16);
        Arrays.fill(content, image.length, content.length, (byte) 'X');
        assertThat(upload(content, "image/png", AssetUsage.AVATAR)).isEqualTo(19L);
        var request = ArgumentCaptor.forClass(ObjectStorageWriteRequest.class);
        verify(storage).store(request.capture());
        assertThat(request.getValue().objectKey()).matches("assets/avatars/[0-9a-f-]{36}\\.png");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().inputStream().readAllBytes()).hasSize(image.length);
        var row = ArgumentCaptor.forClass(MediaAssetDO.class);
        verify(assets).insert(row.capture());
        assertThat(row.getValue().getOriginalFilename()).isEqualTo("image.png");
        assertThat(row.getValue().getWidth()).isEqualTo(12);
        assertThat(row.getValue().getHeight()).isEqualTo(15);
        assertThat(row.getValue().getSha256()).hasSize(32);
        assertThat(row.getValue().getSourceType()).isEqualTo(2);
        assertThat(manager.commits).isEqualTo(2);
        verify(storage, never()).delete(any());
    }

    @Test
    void sharedCoreSupportsImagesAndAttachmentsUsingMetadataInsteadOfCallerPaths() throws Exception {
        assertThat(validator.maxBytes(AssetUsage.IMAGE)).isEqualTo(8388608);
        assertThat(upload(image("png", 2049, 1), "image/png", AssetUsage.IMAGE)).isEqualTo(19L);
        var request = ArgumentCaptor.forClass(ObjectStorageWriteRequest.class);
        verify(storage).store(request.capture());
        assertThat(request.getValue().objectKey()).matches("assets/images/[0-9a-f-]{36}\\.png");
    }

    @Test
    void attachmentHasNoImageMetadataAndRetainsUploaderProvenance() {
        component.upload(new AssetUploadRequest(AssetUsage.ATTACHMENT, 7L, "note.md",
            "application/octet-stream", new byte[] {1}, null, null));
        var row = ArgumentCaptor.forClass(MediaAssetDO.class);
        verify(assets).insert(row.capture());
        assertThat(row.getValue().getAssetType()).isEqualTo(2);
        assertThat(row.getValue().getUsage()).isEqualTo("ATTACHMENT");
        assertThat(row.getValue().getUploaderAccountId()).isEqualTo(7L);
        assertThat(row.getValue().getWidth()).isNull();
    }

    @Test
    void missingUsageOrInvalidUploaderFailsBeforePersistence() {
        assertThatThrownBy(() -> component.upload(new AssetUploadRequest(null, 7L, "note", "text/plain",
            new byte[] {1}, null, null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> component.upload(new AssetUploadRequest(AssetUsage.ATTACHMENT, 0L, "note", "text/plain",
            new byte[] {1}, null, null))).isInstanceOf(ApiException.class);
        verifyNoInteractions(assets, storage);
    }

    @Test
    void mimeLargerThanDatabaseColumnIsRejectedBeforePersistenceOrUpload() {
        // media_asset.mime_type 是 VARCHAR(100)；合法 MIME 语法也不能绕过持久化容量。
        String oversizedMime = "application/" + "x".repeat(89);
        assertThatThrownBy(() -> component.upload(new AssetUploadRequest(
                AssetUsage.ATTACHMENT, 7L, "note", oversizedMime, new byte[] {1}, null, null)))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(assets, storage);
    }

    @Test
    void jpegContentProducesJpgObject() throws Exception {
        assertThat(upload(image("jpeg", 12, 15), "image/jpeg", AssetUsage.AVATAR)).isEqualTo(19L);
        var request = ArgumentCaptor.forClass(ObjectStorageWriteRequest.class);
        verify(storage).store(request.capture());
        assertThat(request.getValue().objectKey()).endsWith(".jpg");
        assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"image/svg+xml", "text/plain", "image/webp", "image/gif"})
    void unsupportedDeclaredMimeIsRejectedWithoutExternalSideEffects(String mime) throws Exception {
        assertThatThrownBy(() -> upload(image("png", 2, 2), mime, AssetUsage.AVATAR))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(assets, storage);
    }

    @Test
    void forgedMimeOrCorruptDataCannotReachObjectStorage() throws Exception {
        assertThatThrownBy(() -> upload(image("png", 2, 2), "image/jpeg", AssetUsage.AVATAR))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> upload("not-an-image".getBytes(), "image/png", AssetUsage.AVATAR))
            .isInstanceOf(ApiException.class);
        verifyNoInteractions(assets, storage);
    }

    @Test
    void oversizedBytesAndDimensionsRejectedBeforeUpload() throws Exception {
        assertThatThrownBy(() -> upload(new byte[2097153], "image/png", AssetUsage.AVATAR))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> upload(image("png", 2049, 1), "image/png", AssetUsage.AVATAR))
            .isInstanceOf(ApiException.class);
        verifyNoInteractions(assets, storage);
    }

    @Test
    void ossFailureKeepsTraceableAssetFailureAndDoesNotLeakVendorMessage() throws Exception {
        doThrow(new ObjectStorageException("sensitive-vendor-url")).when(storage).store(any());
        assertThatThrownBy(() -> upload(image("png", 2, 2), "image/png", AssetUsage.AVATAR))
            .isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.code()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
                assertThat(error.getMessage()).doesNotContain("sensitive-vendor-url");
            });
        assertThat(manager.commits).isEqualTo(2);
        verify(assets).update(isNull(), any());
        verify(storage, never()).delete(any());
    }

    @Test
    void pendingPersistenceFailureDoesNotWriteObject() throws Exception {
        when(assets.insert(any(MediaAssetDO.class))).thenThrow(new IllegalStateException("test-pending-failure"));
        assertThatThrownBy(() -> upload(image("png", 2, 2), "image/png", AssetUsage.AVATAR))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(storage);
        assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test
    void readyPersistenceFailureDoesNotReturnSuccessfulAsset() throws Exception {
        when(assets.update(isNull(), any())).thenReturn(0, 1);
        assertThatThrownBy(() -> upload(image("png", 2, 2), "image/png", AssetUsage.AVATAR))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.INTERNAL_ERROR));
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isEqualTo(2);
        verify(storage, never()).delete(any());
    }

    @Test
    void configCannotRaiseResourceBoundaries() {
        assertThatThrownBy(() -> new AvatarUploadProperties(2097153, 2048))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AvatarUploadProperties(2097152, 2049))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(validator.maxBytes(AssetUsage.AVATAR)).isEqualTo(2097152);
    }

    private byte[] image(String format, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, bytes)).isTrue();
        return bytes.toByteArray();
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
