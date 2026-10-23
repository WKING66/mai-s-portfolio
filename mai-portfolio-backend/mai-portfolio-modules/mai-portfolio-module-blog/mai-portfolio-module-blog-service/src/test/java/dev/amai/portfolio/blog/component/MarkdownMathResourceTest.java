package dev.amai.portfolio.blog.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.enums.ContentStage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MarkdownMathResourceTest {
    @Test
    void mathNeverCreatesResourcesOrChangesSource() {
        var files = mock(AssetFileService.class);
        var assets = mock(AssetQueryService.class);
        var remote = mock(RemoteResourceFetcher.class);
        var resolver = new DocumentResourceResolver(files, assets, remote, new DocumentProperties());
        for (String source : List.of(
                "$[fake](files/math.txt)$",
                "$![fake](https://example.com/math.png)$",
                "$[fake](asset://9)$",
                "$$\n![fake](files/math.png)\n$$",
                "\\([fake](files/math.txt)\\)",
                "\\[\n![fake](files/math.png)\n\\]",
                "$[fake][m]$\n\n[m]: files/math.txt",
                "$2^{i-1} + [fake](files/math.txt)$")) {
            var prepared = resolver.prepare(source, Map.of(), Map.of(), false, 7);
            assertThat(prepared.content()).as(source).isEqualTo(source);
            assertThat(prepared.resources()).as(source).isEmpty();
            assertThat(prepared.newlyCreatedAssets()).isEmpty();
        }
        verifyNoInteractions(files, assets, remote);
    }

    @Test
    void exportRewritesOnlyRealResourcesAndLeavesMathReferencesUntouched() {
        var assets = mock(AssetQueryService.class);
        String source = "$![fake](resource://fake)$\n\n[real](resource://real)";
        var document = new Document(1, 2, 1, ContentStage.DRAFT, true, false,
                "article", "", source, List.of(), List.of());
        String exported = new DocumentRenderer(assets).markdown(
                document, Map.of("fake", "resources/fake.png", "real", "resources/real.pdf"));
        assertThat(exported).isEqualTo(
                "$![fake](resource://fake)$\n\n[real](resources/real.pdf)");
        verifyNoInteractions(assets);
    }
}
