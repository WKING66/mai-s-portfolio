package dev.amai.portfolio.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownMathTest {
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

    private String rewrite(String source, List<String> targets) {
        return MarkdownProcessor.rewriteDestinations(source, (uri, image) -> {
            targets.add(uri);
            return "resolved://" + uri;
        });
    }
}
