package dev.amai.portfolio.blog.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.enums.ContentStage;
import java.util.ArrayList;
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
    void sharedDefinitionsAndSurroundingRealReferencesRemainUsable() {
        String source = "[before](files/before.txt) $[fake][m]$ [real][m]\n\n[m]: files/shared.txt";
        var targets = new ArrayList<String>();
        String rewritten = rewrite(source, targets);
        assertThat(targets).containsExactly("files/before.txt", "files/shared.txt");
        assertThat(rewritten).isEqualTo(
                "[before](resolved://files/before.txt) $[fake][m]$ [real](resolved://files/shared.txt)"
                        + "\n\n[m]: files/shared.txt");
    }

    @Test
    void rewritingAnOuterLinkPreservesMathInItsLabel() {
        String source = "[label $[fake](files/math.txt)$](files/real.txt)";
        var targets = new ArrayList<String>();
        assertThat(rewrite(source, targets))
                .isEqualTo("[label $[fake](files/math.txt)$](resolved://files/real.txt)");
        assertThat(targets).containsExactly("files/real.txt");
    }

    @Test
    void definitionsInsideDisplayMathDoNotDefineOutsideAttachments() {
        String source = "$$\n\n[m]: files/fake.txt\n\n$$\n\n[outside][m]\n\n[real](files/real.txt)";
        var targets = new ArrayList<String>();
        assertThat(rewrite(source, targets))
                .isEqualTo(source.replace("(files/real.txt)", "(resolved://files/real.txt)"));
        assertThat(targets).containsExactly("files/real.txt");
    }

    @Test
    void escapedUnclosedCurrencyAndCodeKeepOrdinaryMarkdownSemantics() {
        for (String source : List.of(
                "\\$[real](files/real.txt)",
                "$x + [real](files/real.txt)",
                "\\([real](files/real.txt)",
                "价格 $5，[real](files/real.txt)，另一个价格 $10",
                "$x\n[real](files/real.txt)$")) {
            var targets = new ArrayList<String>();
            assertThat(rewrite(source, targets))
                    .as(source)
                    .isEqualTo(source.replace("(files/real.txt)", "(resolved://files/real.txt)"));
            assertThat(targets).as(source).containsExactly("files/real.txt");
        }
        for (String source : List.of(
                "`$[fake](files/code.txt)$`",
                "~~~txt\n$[fake](files/code.txt)$\n~~~",
                "~~~mermaid\n%% [fake](files/code.txt)\n~~~")) {
            var targets = new ArrayList<String>();
            assertThat(rewrite(source, targets)).isEqualTo(source);
            assertThat(targets).isEmpty();
        }
    }

    @Test
    void unclosedDisplayMathCannotBorrowAClosingMarkerFromAnotherContainer() {
        for (String source : List.of(
                "> $$\n> [real](files/real.txt)\n\noutside\n$$",
                "- $$\n  [real](files/real.txt)\n\noutside\n$$",
                "> $$\n> [real](files/real.txt)\n\n> $$")) {
            var targets = new ArrayList<String>();
            assertThat(rewrite(source, targets))
                    .as(source)
                    .isEqualTo(source.replace("(files/real.txt)", "(resolved://files/real.txt)"));
            assertThat(targets).as(source).containsExactly("files/real.txt");
        }
    }

    @Test
    void mathInContainersAndMultilineLabelsKeepsItsOriginalSource() {
        for (String source : List.of(
                "> $$\n>\n> [fake](files/math.txt)\n>\n> $$\n>\n> [real](files/real.txt)",
                "- article\n\n  $$\n  [fake](files/math.txt)\n\n  x\n  $$\n\n  [real](files/real.txt)",
                "[label \\(x\r\nx + [fake](files/math.txt)\\)](files/real.txt)",
                "![label $[fake](files/math.txt)$](files/real.txt)",
                "\\\\$[fake](files/math.txt)$ [real](files/real.txt)",
                "$x + \\$ + [fake](files/math.txt)$ [real](files/real.txt)",
                "$x + \\\\$ [real](files/real.txt)")) {
            var targets = new ArrayList<String>();
            assertThat(rewrite(source, targets))
                    .as(source)
                    .isEqualTo(source.replace("(files/real.txt)", "(resolved://files/real.txt)"));
            assertThat(targets).as(source).containsExactly("files/real.txt");
        }
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

    private String rewrite(String source, List<String> targets) {
        return MarkdownDestinations.rewrite(source, (uri, image) -> {
            targets.add(uri);
            return "resolved://" + uri;
        });
    }
}
