package dev.amai.portfolio.blog.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.asset.api.AssetMetadataData;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import dev.amai.portfolio.blog.enums.ResourceSource;
import dev.amai.portfolio.blog.enums.ResourceStatus;
import dev.amai.portfolio.blog.enums.ResourceType;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DocumentPreparationTest {
    private final DocumentProperties limits = new DocumentProperties();
    private final AssetFileService assetFileService = mock(AssetFileService.class);
    private final AssetQueryService assetQueryService = mock(AssetQueryService.class);
    private final RemoteResourceFetcher remoteResourceFetcher = mock(RemoteResourceFetcher.class);
    private final DocumentResourceResolver resolver = new DocumentResourceResolver(
            assetFileService, assetQueryService, remoteResourceFetcher, limits);

    @Test
    void deduplicatesWithinTypeWithoutMixingImageAndAttachmentOrConsumingExtraSlots() {
        limits.setMaxResources(2);
        when(assetFileService.upload(any(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(31L, 32L);
        var input = new DocumentInputResource("a.png", "image/png", new byte[] {1});
        var prepared = resolver.prepare(
                "![one](a.png) [file](a.png) ![again](a.png) [again](a.png)",
                Map.of(), Map.of("a.png", input), false, 7);

        assertThat(prepared.resources()).extracting(DocumentResource::type)
                .containsExactly(ResourceType.IMAGE, ResourceType.ATTACHMENT);
        assertThat(prepared.resources()).extracting(DocumentResource::assetId).containsExactly(31L, 32L);
        assertThat(prepared.newlyCreatedAssets()).containsExactlyInAnyOrder(31L, 32L);
        verify(assetFileService).upload(AssetUsage.IMAGE, 7, "a.png", "image/png", input.content());
        verify(assetFileService).upload(AssetUsage.ATTACHMENT, 7, "a.png", "image/png", input.content());
        verifyNoInteractions(assetQueryService, remoteResourceFetcher);
    }

    @Test
    void capacityFailureCompensatesOnlyNewUploadsNotHistoryOrExistingAssetReferences() {
        limits.setMaxResources(3);
        var history = new DocumentResource(
                "history", ResourceType.IMAGE, ResourceSource.LOCAL, "old.png", 11L, ResourceStatus.READY);
        when(assetQueryService.findReadyMetadata(19L)).thenReturn(Optional.of(
                new AssetMetadataData(19L, AssetType.IMAGE, AssetUsage.IMAGE, 7L)));
        when(assetFileService.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(31L);
        var input = new DocumentInputResource("new.png", "image/png", new byte[] {1});

        assertThatThrownBy(() -> resolver.prepare(
                "![old](resource://history) ![existing](asset://19) ![new](new.png) ![extra](extra.png)",
                Map.of("history", history), Map.of("new.png", input), false, 7))
                .isInstanceOf(ApiException.class).hasMessage("资源数量超过限制");
        verify(assetFileService).discardUnbound(Set.of(31L), 7L);
        verify(assetFileService, times(1)).upload(any(), anyLong(), anyString(), anyString(), any());
        verify(assetQueryService).findReadyMetadata(19L);
    }

    @Test
    void duplicateFailedRemoteReferencesRetryOnceAndKeepTheirStableKey() throws Exception {
        var failed = new DocumentResource(
                "retry", ResourceType.IMAGE, ResourceSource.REMOTE,
                "https://example.test/a.png", null, ResourceStatus.FETCH_FAILED);
        when(remoteResourceFetcher.fetch(anyString(), anyInt(), anyLong())).thenReturn(
                new DocumentInputResource("a.png", "image/png", new byte[] {1}, ResourceSource.REMOTE));
        when(assetFileService.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(31L);

        var prepared = resolver.prepare(
                "![one](resource://retry) ![two](resource://retry)",
                Map.of("retry", failed), Map.of(), true, 7);
        assertThat(prepared.resources()).singleElement().satisfies(resource -> {
            assertThat(resource.key()).isEqualTo("retry");
            assertThat(resource.status()).isEqualTo(ResourceStatus.READY);
            assertThat(resource.assetId()).isEqualTo(31L);
        });
        verify(remoteResourceFetcher, times(1)).fetch(anyString(), anyInt(), anyLong());
        verify(assetFileService, times(1)).upload(any(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    void failedHistoryWithoutRightsIsReusedWithoutStartingNetworkOrUpload() {
        var failed = new DocumentResource(
                "retry", ResourceType.IMAGE, ResourceSource.REMOTE,
                "https://example.test/a.png", null, ResourceStatus.FETCH_FAILED);
        var prepared = resolver.prepare(
                "![one](resource://retry)", Map.of("retry", failed), Map.of(), false, 7);
        assertThat(prepared.resources()).containsExactly(failed);
        assertThat(prepared.newlyCreatedAssets()).isEmpty();
        verifyNoInteractions(remoteResourceFetcher, assetFileService, assetQueryService);
    }

    @Test
    void unexpectedFailureKeepsOriginalExceptionAndCompensatesEarlierUploads() {
        var failure = new IllegalStateException("upload interrupted");
        when(assetFileService.upload(any(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(31L).thenThrow(failure);
        var first = new DocumentInputResource("a.png", "image/png", new byte[] {1});
        var second = new DocumentInputResource("b.png", "image/png", new byte[] {2});

        assertThatThrownBy(() -> resolver.prepare(
                "![a](a.png) ![b](b.png)", Map.of(), Map.of("a.png", first, "b.png", second), false, 7))
                .isSameAs(failure);
        verify(assetFileService).discardUnbound(Set.of(31L), 7L);
    }

    @Test
    void normalizedBodyOverflowCompensatesFilesCreatedBeforeTheFinalSizeCheck() {
        limits.setMaxContentBytes(24);
        when(assetFileService.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(31L);
        var input = new DocumentInputResource("a.png", "image/png", new byte[] {1});

        assertThatThrownBy(() -> resolver.prepare(
                "![a](a.png)", Map.of(), Map.of("a.png", input), false, 7))
                .isInstanceOf(ApiException.class).hasMessage("规范化正文超过限制");
        verify(assetFileService).discardUnbound(Set.of(31L), 7L);
    }

    @Test
    void everyPreparationOwnsItsStateAndAllFetchesWithinItShareTheSameDeadline() throws Exception {
        when(remoteResourceFetcher.fetch(anyString(), anyInt(), anyLong())).thenReturn(
                new DocumentInputResource("a.png", "image/png", new byte[] {1}, ResourceSource.REMOTE));
        when(assetFileService.upload(any(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(31L, 32L, 33L);

        var first = resolver.prepare(
                "![a](https://example.test/a.png) ![b](https://example.test/b.png)",
                Map.of(), Map.of(), true, 7);
        var second = resolver.prepare(
                "![a](https://example.test/a.png)", Map.of(), Map.of(), true, 7);
        assertThat(first.newlyCreatedAssets()).containsExactlyInAnyOrder(31L, 32L);
        assertThat(second.newlyCreatedAssets()).containsExactly(33L);
        assertThat(second.resources()).hasSize(1);
        var deadlines = ArgumentCaptor.forClass(Long.class);
        verify(remoteResourceFetcher, times(3)).fetch(anyString(), anyInt(), deadlines.capture());
        assertThat(deadlines.getAllValues().get(0)).isEqualTo(deadlines.getAllValues().get(1));
    }
}
