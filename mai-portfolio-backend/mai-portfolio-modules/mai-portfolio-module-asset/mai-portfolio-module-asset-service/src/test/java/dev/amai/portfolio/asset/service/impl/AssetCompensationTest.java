package dev.amai.portfolio.asset.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.asset.api.AssetUploadComponent;
import dev.amai.portfolio.asset.component.ImageContentValidator;
import dev.amai.portfolio.asset.config.AvatarUploadProperties;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.ObjectStorage;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AssetCompensationTest {
    private final MediaAssetMapper mediaAssetMapper = mock(MediaAssetMapper.class);
    private final ObjectStorage objectStorage = mock(ObjectStorage.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final AssetFileServiceImpl assetFileService = new AssetFileServiceImpl(
            new ImageContentValidator(new AvatarUploadProperties(2097152, 2048)),
            mock(AssetUploadComponent.class), mediaAssetMapper, objectStorage, transactionManager);

    @BeforeEach
    void asset() {
        var asset = new MediaAssetDO();
        asset.setId(19L);
        asset.setUploaderAccountId(7L);
        asset.setStorageKey("assets/images/test.png");
        when(mediaAssetMapper.selectOne(any())).thenReturn(asset);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void referencedAssetIsNeverPhysicallyDeleted() {
        when(mediaAssetMapper.isReferenced(19)).thenReturn(true);

        assetFileService.discardUnbound(Set.of(19L), 7);

        verify(mediaAssetMapper, never()).deleteById(anyLong());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void anotherUploadersAssetIsNotACleanupCandidate() {
        assetFileService.discardUnbound(Set.of(19L), 8);

        verify(mediaAssetMapper, never()).isReferenced(anyLong());
        verify(mediaAssetMapper, never()).deleteById(anyLong());
        verifyNoInteractions(objectStorage);
    }

    @Test
    void metadataCommitPrecedesObjectDeletion() {
        when(mediaAssetMapper.deleteById(19L)).thenReturn(1);

        assetFileService.discardUnbound(Set.of(19L), 7);

        var order = inOrder(mediaAssetMapper, transactionManager, objectStorage);
        order.verify(mediaAssetMapper).selectOne(any());
        order.verify(mediaAssetMapper).isReferenced(19L);
        order.verify(mediaAssetMapper).deleteById(19L);
        order.verify(transactionManager).commit(any());
        order.verify(objectStorage).delete("assets/images/test.png");
    }

    @Test
    void externalDeleteFailureDoesNotReplaceOriginalBusinessFailure() {
        when(mediaAssetMapper.deleteById(19L)).thenReturn(1);
        doThrow(new IllegalStateException("storage unavailable"))
                .when(objectStorage).delete("assets/images/test.png");

        assertThatCode(() -> assetFileService.discardUnbound(Set.of(19L), 7)).doesNotThrowAnyException();
        verify(transactionManager).commit(any());
    }
}
