package dev.amai.portfolio.asset.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.enums.MediaAssetType;
import dev.amai.portfolio.asset.enums.MediaStatus;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.ObjectStorage;
import dev.amai.portfolio.storage.StoredObjectAccessUrl;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AssetDownloadUrlTest {
    @BeforeAll
    static void initializeMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "attachment-download-test"),
                MediaAssetDO.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", "archive.tar.gz", "说明 文档+v2.docx", "table.xlsx", "README"})
    void attachmentProjectionUsesStoredFilenameForExistingBinObjects(String filename) {
        MediaAssetMapper mediaAssetMapper = mock(MediaAssetMapper.class);
        ObjectStorage objectStorage = mock(ObjectStorage.class);
        var asset = readyAsset(AssetType.FILE, "ATTACHMENT", filename, "assets/attachments/legacy.bin");
        when(mediaAssetMapper.selectList(any())).thenReturn(List.of(asset));
        var access = new StoredObjectAccessUrl("https://oss.test/download", Instant.now().plusSeconds(600));
        when(objectStorage.downloadUrl(asset.getStorageKey(), filename)).thenReturn(access);

        var result = new AssetQueryServiceImpl(mediaAssetMapper, objectStorage)
                .findReady(Set.of(asset.getId()), AssetType.FILE);

        assertThat(result.get(asset.getId()).resourceUrl()).isEqualTo(access.url());
        assertThat(result.get(asset.getId()).expiresAt()).isEqualTo(access.expiresAt());
        verify(objectStorage).downloadUrl(asset.getStorageKey(), filename);
        verifyNoMoreInteractions(objectStorage);
    }

    @Test
    void imagesKeepDisplayUrlsWithoutForcingDownload() {
        MediaAssetMapper mediaAssetMapper = mock(MediaAssetMapper.class);
        ObjectStorage objectStorage = mock(ObjectStorage.class);
        var asset = readyAsset(AssetType.IMAGE, "IMAGE", "image.png", "assets/images/image.png");
        when(mediaAssetMapper.selectList(any())).thenReturn(List.of(asset));
        var access = new StoredObjectAccessUrl("https://oss.test/image", Instant.now().plusSeconds(600));
        when(objectStorage.accessUrl(asset.getStorageKey())).thenReturn(access);

        var result = new AssetQueryServiceImpl(mediaAssetMapper, objectStorage)
                .findReady(Set.of(asset.getId()), AssetType.IMAGE);

        assertThat(result.get(asset.getId()).resourceUrl()).isEqualTo(access.url());
        verify(objectStorage).accessUrl(asset.getStorageKey());
        verifyNoMoreInteractions(objectStorage);
    }

    @Test
    void mixedDocumentAssetsUseOneMetadataQueryAndKeepEachDownloadMode() {
        MediaAssetMapper mediaAssetMapper = mock(MediaAssetMapper.class);
        ObjectStorage objectStorage = mock(ObjectStorage.class);
        var image = readyAsset(AssetType.IMAGE, "IMAGE", "image.png", "assets/image.png");
        var attachment = readyAsset(AssetType.FILE, "ATTACHMENT", "原文件.pdf", "assets/file.bin");
        attachment.setId(20L);
        when(mediaAssetMapper.selectList(any())).thenReturn(List.of(image, attachment));
        when(objectStorage.accessUrl(image.getStorageKey())).thenReturn(
                new StoredObjectAccessUrl("https://oss.test/image", Instant.now().plusSeconds(600)));
        when(objectStorage.downloadUrl(attachment.getStorageKey(), attachment.getOriginalFilename())).thenReturn(
                new StoredObjectAccessUrl("https://oss.test/file", Instant.now().plusSeconds(600)));

        var result = new AssetQueryServiceImpl(mediaAssetMapper, objectStorage)
                .findReady(Set.of(image.getId(), attachment.getId()));

        assertThat(result).hasSize(2);
        verify(mediaAssetMapper).selectList(any());
        verifyNoMoreInteractions(mediaAssetMapper);
        verify(objectStorage).accessUrl(image.getStorageKey());
        verify(objectStorage).downloadUrl(attachment.getStorageKey(), "原文件.pdf");
        verifyNoMoreInteractions(objectStorage);
    }

    private static MediaAssetDO readyAsset(
            AssetType type, String usage, String filename, String storageKey) {
        var asset = new MediaAssetDO();
        asset.setId(19L);
        asset.setType(MediaAssetType.from(type).code());
        asset.setStatus(MediaStatus.READY.code());
        asset.setUsage(AssetUsage.valueOf(usage).code());
        asset.setOriginalFilename(filename);
        asset.setStorageKey(storageKey);
        asset.setContentType(type == AssetType.IMAGE ? "image/png" : "application/octet-stream");
        asset.setByteSize(12L);
        return asset;
    }
}
