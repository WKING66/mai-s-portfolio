package dev.amai.portfolio.blog.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import dev.amai.portfolio.blog.enums.ContentStage;
import dev.amai.portfolio.blog.enums.ResourceSource;
import dev.amai.portfolio.blog.enums.ResourceStatus;
import dev.amai.portfolio.blog.enums.ResourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentRendererTest {
    private final AssetQueryService assetQueryService = mock(AssetQueryService.class);
    private final DocumentRenderer renderer = new DocumentRenderer(assetQueryService);

    @Test
    void missingAttachmentKeepsItsDraftIssueAlongsideReadyResources() {
        var ready = new DocumentResource("ready", ResourceType.ATTACHMENT, ResourceSource.LOCAL,
                "report.pdf", 9L, ResourceStatus.READY);
        var missing = new DocumentResource("missing", ResourceType.ATTACHMENT, ResourceSource.LOCAL,
                "missing/report.pdf", null, ResourceStatus.INVALID, "未提供本地文件：missing/report.pdf");
        var asset = new AssetResourceData(9L, AssetType.FILE, AssetUsage.ATTACHMENT, 7L,
                "report.pdf", "application/pdf", 10, "https://example.test/signed",
                Instant.parse("2030-01-01T00:00:00Z"));
        when(assetQueryService.findReady(Set.of(9L))).thenReturn(Map.of(9L, asset));

        var resources = renderer.resources(document(List.of(ready, missing)), true);

        assertThat(resources).hasSize(2);
        assertThat(resources.getFirst().resourceUrl()).isEqualTo(asset.resourceUrl());
        assertThat(resources.getLast().status()).isEqualTo(ResourceStatus.INVALID);
        assertThat(resources.getLast().assetId()).isNull();
        assertThat(resources.getLast().resourceUrl()).isNull();
        assertThat(resources.getLast().errorMessage()).isEqualTo(missing.errorMessage());
        verify(assetQueryService).findReady(Set.of(9L));
    }

    @Test
    void failedResourcesWithNoAssetsDoNotDependOnMapNullKeySupport() {
        var failed = new DocumentResource("failed", ResourceType.IMAGE, ResourceSource.REMOTE,
                "https://example.test/missing.png", null, ResourceStatus.FETCH_FAILED, "远程图片获取失败");
        when(assetQueryService.findReady(Set.of())).thenReturn(Map.of());

        var resources = renderer.resources(document(List.of(failed)), false);

        assertThat(resources).singleElement().satisfies(resource -> {
            assertThat(resource.status()).isEqualTo(ResourceStatus.FETCH_FAILED);
            assertThat(resource.resourceUrl()).isNull();
            assertThat(resource.originalUri()).isNull();
            assertThat(resource.errorMessage()).isNull();
        });
    }

    private Document document(List<DocumentResource> resources) {
        return new Document(1, 2, 1, ContentStage.DRAFT, true, false,
                "article", "", "body", resources, List.of());
    }
}
