package dev.amai.portfolio.markdown;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import org.commonmark.node.Link;
import org.commonmark.parser.Parser;
import org.junit.jupiter.api.Test;

class MarkdownProcessorTest {
    @Test
    void changingOnlyNestedImagePreservesTheOuterLinkSourceExactly() {
        String body = "[![图](a.png)](https://example.test \" Site Title \")\r\n";
        String result = MarkdownProcessor.rewriteDestinations(body, (uri, image) -> image ? "assets/a.png" : uri);
        assertThat(result).isEqualTo(body.replace("a.png", "assets/a.png"));
    }

    @Test
    void changedOuterLinkIncludesChildChangesOnceAndKeepsVisitorOrder() {
        String body = "前言 😀\r\n\r\n[![图](a.png)](report.pdf)\r\n\r\n末尾\r\n";
        var visits = new ArrayList<Boolean>();
        String result = MarkdownProcessor.rewriteDestinations(body, (uri, image) -> {
            visits.add(image);
            return image ? "assets/a.png" : "files/report.pdf";
        });
        assertThat(visits).containsExactly(false, true);
        assertThat(result).isEqualTo(body.replace("a.png", "assets/a.png").replace("report.pdf", "files/report.pdf"));
    }

    @Test
    void disjointEditsUseOriginalUnicodeOffsetsAndDoNotRewriteCodeExamples() {
        String body = "😀 前缀\n\n![一](a.png) 和 ![二](b.png)\n\n`![inline](a.png)`\n\n```md\n![code](b.png)\n```\n";
        Map<String, String> destinations = Map.of("a.png", "long/path/a.png", "b.png", "x");
        String result = MarkdownProcessor.rewriteDestinations(body, (uri, image) -> destinations.getOrDefault(uri, uri));
        assertThat(result).isEqualTo(body.replace("![一](a.png)", "![一](long/path/a.png)")
                .replace("![二](b.png)", "![二](x)"));
    }

    @Test
    void unchangedReferencesPreserveAllBytesAndImportedHeadingStillUsesOnlyTextNodes() {
        String body = "# 文本 *强调* `code` [链接](https://example.test)\r\n\r\n![图](a.png \" title \")\r\n";
        assertThat(MarkdownProcessor.rewriteDestinations(body, (uri, image) -> uri)).isEqualTo(body);
        assertThat(MarkdownProcessor.firstHeading(body)).hasValue("文本 强调  链接");
    }

    @Test
    void signedQueryEscapesSurviveMarkdownRewriteAndParsingExactly() {
        String signed = "https://oss.example/legacy.bin?response-content-disposition="
                + "attachment%3B%20filename%3D%22name%25E9%25A1%25B9.md%22"
                + "%3B%20filename%2A%3DUTF-8%27%27name%25E9%25A1%25B9.md"
                + "&Expires=1792144299&Signature=test%2Bvalue%2F";
        String markdown = MarkdownProcessor.rewriteDestinations("[附件](resource://file)", (uri, image) -> signed);
        assertThat(markdown).isEqualTo("[附件](" + signed + ")");
        var ast = Parser.builder().build().parse(markdown);
        var link = (Link) ast.getFirstChild().getFirstChild();
        assertThat(link.getDestination()).isEqualTo(signed);
    }

    @Test
    void strictUtf8NormalizationRemovesOnlyBomAndNormalizesLineEndings() throws Exception {
        assertThat(MarkdownProcessor.normalizeUtf8("\ufeff# 标题\r\n\r正文\n".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("# 标题\n\n正文\n");
        assertThatThrownBy(() -> MarkdownProcessor.normalizeUtf8(new byte[] {(byte) 0xff}))
                .isInstanceOf(CharacterCodingException.class);
    }

    @Test
    void missingHeadingDoesNotInventATitleAndAnEmptyHeadingStaysExplicit() {
        assertThat(MarkdownProcessor.firstHeading("body\n\n> # quoted\n\n## second")).isEmpty();
        assertThat(MarkdownProcessor.firstHeading("#\n")).hasValue("");
    }
}
