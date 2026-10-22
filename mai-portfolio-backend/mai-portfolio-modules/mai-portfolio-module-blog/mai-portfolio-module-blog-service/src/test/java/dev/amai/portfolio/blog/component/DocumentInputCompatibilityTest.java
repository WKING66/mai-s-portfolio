package dev.amai.portfolio.blog.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.convert.DocumentConvert;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentInputCompatibilityTest {
    private final AssetFileService assetFileService = mock(AssetFileService.class);
    private final AssetQueryService assetQueryService = mock(AssetQueryService.class);
    private final RemoteResourceFetcher remoteResourceFetcher = mock(RemoteResourceFetcher.class);
    private final DocumentResourceResolver resolver = new DocumentResourceResolver(
            assetFileService, assetQueryService, remoteResourceFetcher, new DocumentProperties());

    @Test
    void embeddedImageUsesOneUploadAndStableReferenceWithoutPersistingBase64OrRequestingNetwork() {
        String uri = "data:image/webp;base64," + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        when(assetFileService.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(31L);
        var prepared = resolver.prepare("![a](" + uri + ") ![b](" + uri + ")", Map.of(), Map.of(), false, 7);

        assertThat(prepared.resources()).singleElement().satisfies(resource -> {
            assertThat(resource.source()).isEqualTo(ResourceSource.EMBEDDED);
            assertThat(resource.status()).isEqualTo(ResourceStatus.READY);
            assertThat(resource.originalUri()).isEqualTo("data:image/webp;base64");
            assertThat(resource.errorMessage()).isNull();
            assertThat(prepared.content()).contains(resource.reference()).doesNotContain("data:");
        });
        verify(assetFileService).upload(AssetUsage.IMAGE, 7, "embedded-image", "image/webp", new byte[] {1, 2, 3});
        verifyNoInteractions(remoteResourceFetcher, assetQueryService);
    }

    @Test
    void embeddedPayloadIsNotSubjectToTheOrdinaryUrlLengthLimit() {
        String uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[3000]);
        when(assetFileService.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(31L);
        var prepared = resolver.prepare("![a](" + uri + ")", Map.of(), Map.of(), false, 7);
        assertThat(prepared.resources().getFirst().status()).isEqualTo(ResourceStatus.READY);
    }

    @Test
    void malformedBase64HasItsOwnPersistableDiagnosticAndDoesNotReachUpload() {
        var prepared = resolver.prepare("![a](data:image/png;base64,%%%broken)", Map.of(), Map.of(), false, 7);
        var failed = prepared.resources().getFirst();
        assertThat(failed.status()).isEqualTo(ResourceStatus.INVALID);
        assertThat(failed.errorMessage()).contains("Base64", "损坏");
        assertThat(failed.originalUri()).doesNotContain("broken");
        var relation = DocumentConvert.MAPPER.to(failed, 12L, 0);
        assertThat(DocumentConvert.MAPPER.to(relation)).isEqualTo(failed);
        verifyNoInteractions(assetFileService, remoteResourceFetcher);
    }

    @Test
    void nonImageAndNonBase64DataAreRejectedWithoutNetworkOrUpload() {
        assertThatThrownBy(() -> DocumentDataUri.decode("data:text/html;base64,WA==", AssetUsage.IMAGE))
                .isInstanceOf(ApiException.class).hasMessageContaining("内嵌图片");
        assertThatThrownBy(() -> DocumentDataUri.decode("data:image/png,raw", AssetUsage.IMAGE))
                .isInstanceOf(ApiException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> DocumentDataUri.decode("data:image/png;base64,WA==", AssetUsage.ATTACHMENT))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void windowsReferenceSeparatorsResolveAgainstTheUploadedRelativePath() {
        when(assetFileService.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(31L);
        var input = new DocumentInputResource("images/a b.png", "image/png", new byte[] {1});
        var prepared = resolver.prepare(
                "![a](images\\a%20b.png)", Map.of(), Map.of("images/a b.png", input), false, 7);
        assertThat(prepared.resources().getFirst().status()).isEqualTo(ResourceStatus.READY);
        verify(assetFileService).upload(AssetUsage.IMAGE, 7, "images/a b.png", "image/png", input.content());
    }

    @Test
    void pathCompatibilityStillRejectsTraversalAbsolutePathsAndEncodedSeparators() {
        for (String path : new String[] {"..\\secret.png", "C:\\secret.png", "\\\\server\\a.png", "images%5ca.png"}) {
            assertThatThrownBy(() -> DocumentResourceLoader.referencePath(path)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> DocumentResourceResolver.localPath("images\\a.png"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void missingLocalFileExplainsWhichInputBoundaryFailedAndPublicViewHidesTheReason() {
        var prepared = resolver.prepare("![a](images/missing.png)", Map.of(), Map.of(), false, 7);
        var failed = prepared.resources().getFirst();
        assertThat(failed.errorMessage()).contains("未找到", "相对路径", "文档文件夹");
        var document = new Document(1L, 2L, 1L, ContentStage.DRAFT, true, false, "title", "", prepared.content(), prepared.resources(), List.of());
        var renderer = new DocumentRenderer(assetQueryService);
        assertThat(renderer.resources(document, true).getFirst().errorMessage()).isEqualTo(failed.errorMessage());
        assertThat(renderer.resources(document, false).getFirst().errorMessage()).isNull();
        verifyNoInteractions(assetFileService, remoteResourceFetcher);
    }
}
