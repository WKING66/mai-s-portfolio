package dev.amai.portfolio.asset.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.asset.component.ImageContentValidator;
import dev.amai.portfolio.asset.config.AvatarUploadProperties;
import dev.amai.portfolio.system.api.*;
import dev.amai.portfolio.web.exception.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import dev.amai.portfolio.security.context.AccountContext;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Optional;

import javax.imageio.ImageIO;

class AssetServiceImplTest {
    private final AssetUploadComponent upload = mock(AssetUploadComponent.class);
    private final AssetQueryService assets = mock(AssetQueryService.class);
    private MockedStatic<AccountContext> identity;

    @BeforeEach
    void openContext() {
        identity = mockStatic(AccountContext.class);
        identity.when(AccountContext::currentAccount).thenReturn(
                new AccountContext.Account(7L, "visitor", java.util.List.of()));
    }

    @AfterEach
    void closeContext() {
        identity.close();
    }
    private final AssetServiceImpl service =
            new AssetServiceImpl(
                    new ImageContentValidator(new AvatarUploadProperties(2097152, 2048)),
                    new AssetFileServiceImpl(
                            new ImageContentValidator(new AvatarUploadProperties(2097152, 2048)),
                            upload,
                            mock(dev.amai.portfolio.asset.mapper.MediaAssetMapper.class),
                            mock(dev.amai.portfolio.storage.ObjectStorage.class),
                            mock(org.springframework.transaction.PlatformTransactionManager.class)),
                    assets);

    @Test
    void sameEntryHandlesAvatarImageAndAttachmentWithDeclaredProvenance() throws Exception {
        when(upload.upload(any())).thenReturn(19L);
        for (AssetUsage usage : AssetUsage.values()) {
            var asset =
                    new AssetResourceData(
                            19L,
                            usage.assetType(),
                            usage,
                            7L,
                            "name",
                            "application/octet-stream",
                            12L,
                            "https://oss.test/file?Signature=secret",
                            Instant.parse("2030-01-01T00:00:00Z"));
            when(assets.findReady(19L, usage.assetType())).thenReturn(Optional.of(asset));
            byte[] bytes;
            if (usage.assetType() == AssetType.IMAGE) {
                var output = new ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
                bytes = output.toByteArray();
            } else
                bytes =
                        "<html>not rendered</html>"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var result =
                    service.upload(
                            new MockMultipartFile(
                                    "file",
                                    "../name.html",
                                    usage.assetType() == AssetType.IMAGE
                                            ? "image/png"
                                            : "text/html",
                                    bytes),
                            usage);
            assertThat(result.resourceUrl()).isEqualTo(asset.resourceUrl());
            assertThat(result.usage()).isEqualTo(usage);
        }
        var requests = ArgumentCaptor.forClass(AssetUploadRequest.class);
        verify(upload, times(3)).upload(requests.capture());
        assertThat(requests.getAllValues())
                .allSatisfy(request -> assertThat(request.uploaderAccountId()).isEqualTo(7L));
        assertThat(requests.getAllValues().get(0).filename()).isEqualTo("name.png");
        assertThat(requests.getAllValues().get(1).filename()).isEqualTo("name.png");
        assertThat(requests.getAllValues().get(2).contentType())
                .isEqualTo("application/octet-stream");
        assertThat(requests.getAllValues().get(2).filename()).isEqualTo("name.html");
    }

    @Test
    void invalidOrOversizedFileDoesNotUploadOrCreateAsset() {
        assertThatThrownBy(() -> service.upload(null, AssetUsage.AVATAR))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        new MockMultipartFile(
                                                "file", "bad.svg", "image/svg+xml", new byte[] {1}),
                                        AssetUsage.AVATAR))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        new MockMultipartFile(
                                                "file",
                                                "large.png",
                                                "image/png",
                                                new byte[2097153]),
                                        AssetUsage.AVATAR))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(upload, assets);
    }

    @Test
    void resourceRefreshChecksUploaderOrOwnerBeforeGeneratingSignature() {
        when(assets.findReadyMetadata(19L))
                .thenReturn(
                        Optional.of(
                                new AssetMetadataData(
                                        19L, AssetType.IMAGE, AssetUsage.AVATAR, 8L)));
        assertThatThrownBy(() -> service.resource(19L)).isInstanceOf(ApiException.class);
        verify(assets, never()).findReady(anyLong(), any(AssetType.class));
        when(assets.findReadyMetadata(19L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resource(19L))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }

    @Test
    void ownAssetCanBeRefreshedWithoutOwnerRole() {
        when(assets.findReadyMetadata(19L))
                .thenReturn(
                        Optional.of(
                                new AssetMetadataData(
                                        19L, AssetType.IMAGE, AssetUsage.AVATAR, 7L)));
        when(assets.findReady(19L, AssetType.IMAGE))
                .thenReturn(
                        Optional.of(
                                new AssetResourceData(
                                        19L,
                                        AssetType.IMAGE,
                                        AssetUsage.AVATAR,
                                        7L,
                                        "image.png",
                                        "image/png",
                                        12L,
                                        "https://oss.test/new",
                                        Instant.parse("2030-01-01T00:00:00Z"))));
        assertThat(service.resource(19L).resourceUrl()).isEqualTo("https://oss.test/new");
        verifyNoInteractions(upload);
    }
}
