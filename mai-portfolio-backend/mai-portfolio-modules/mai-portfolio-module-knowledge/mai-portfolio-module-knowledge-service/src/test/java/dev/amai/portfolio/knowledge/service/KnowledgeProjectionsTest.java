package dev.amai.portfolio.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.amai.portfolio.document.api.DocumentKnowledgeSource.Formal;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KnowledgeProjectionsTest {
    @Test
    void canonicalBytesMatchPythonJsonDumps() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tags", List.of("中文", "emoji 😀"));
        fields.put("absent", null);
        fields.put("content", Map.of("z", "a\n\u000b\"\\", "a", 42));
        String json = KnowledgeProjections.canonical(fields);
        assertThat(json).isEqualTo("{\"content\":{\"a\":42,\"z\":\"a\\n\\u000b\\\"\\\\\"},\"tags\":[\"中文\",\"emoji 😀\"]}");
        assertThat(HexFormat.of().formatHex(KnowledgeProjections.hash(fields)))
                .isEqualTo("9032c604c421ba1482310d0a977e2e3a84857672b94bb4c6d69fef7b8311cd1a");
        assertThatThrownBy(() -> KnowledgeProjections.hash(Map.of("float", 1.25)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signedUrlRedactionPreservesAdjacentMarkdownWithoutSpaces() {
        String markdown = "参考[文档](https://example.com/a)和图片![图](https://oss.example/img.png?Signature=abc&Expires=1)结尾";
        assertThat(projectMarkdown(markdown))
                .isEqualTo("参考[文档](https://example.com/a)和图片![图]()结尾");
    }

    @Test
    void signedUrlRedactionHandlesNestedParenthesesAngleBracketsAndLongUrls() {
        String longPath = "a".repeat(20_000);
        String longQuery = "x=1&amp;".repeat(5_000);
        String markdown = "![图0](https://oss.example/0.png?Signature=0)"
                + "保留[正常](https://example.com/" + longPath + "/a(b(c)).png)"
                + "移除[图1](https://oss.example/" + longPath + "/a(b(c)).png?" + longQuery + "Signature=abc)"
                + "和[图2](<https://oss.example/a(b).png?Signature=def&Expires=2>)"
                + "及[图3](<https://oss.example/a(b.png?X-Oss-Signature=ghi&Expires=3>)";
        assertThat(projectMarkdown(markdown))
                .isEqualTo("![图0]()保留[正常](https://example.com/" + longPath + "/a(b(c)).png)"
                        + "移除[图1]()和[图2](<>)及[图3](<>)");
    }

    @Test
    void signedUrlRedactionDistinguishesIpv6HostFromClosingBracket() {
        String pathBracket = "https://example.com/a[b]?Signature=example";
        String ipv6PathBracket = "https://[2001:db8::1]/a[b]?Signature=example";
        String markdown = "[图](https://[2001:db8::1]/a.png?Signature=abc)"
                + "[http://user@[2001:db8::2]:8080?Signature=def] " + pathBracket + " " + ipv6PathBracket;

        assertThat(projectMarkdown(markdown)).isEqualTo("[图]()[] " + pathBracket + " " + ipv6PathBracket);
    }

    @Test
    void signedUrlRedactionRespectsMarkdownEscapes() {
        String publicLink = "[公开](https://example.com/a\\\\)?Signature=example";
        String markdown = "[图](https://oss.example/a\\).png?Signature=abc)"
                + "和[图2](https://oss.example/a\\(.png?Signature=def)"
                + "及[图3](<https://oss.example/a\\>.png?Signature=ghi>)" + publicLink;

        assertThat(projectMarkdown(markdown)).isEqualTo("[图]()和[图2]()及[图3](<>)" + publicLink);
    }

    @Test
    void signedUrlRedactionRecognizesHtmlQuerySeparators() {
        String publicLink = "<a href=\"https://example.com/a?Signature=&amp;page=2\">公开</a>";
        String html = "<img src=\"https://oss.example/a.png?Expires=1&amp;OSSAccessKeyId=abc\">"
                + "<img src=\"https://oss.example/b.png?Expires=2&AmP;X-Amz-Signature=def\">"
                + "<img src=\"https://oss.example/c.png?Expires=3&Signature=ghi\">" + publicLink;

        assertThat(projectMarkdown(html)).isEqualTo("<img src=\"\"><img src=\"\"><img src=\"\">" + publicLink);
    }

    @Test
    void signedUrlRedactionChecksOnlyQueryParameters() {
        String publicLinks = "https://example.com/a#section?Signature=example "
                + "[带片段](https://example.com/a?page=2#section&Signature=example) "
                + "[查询值](https://example.com/a?next=/b?Signature=example) "
                + "[路径](https://example.com/a&Signature=example) "
                + "[空签名](https://example.com/a?Signature=#section)";
        String markdown = publicLinks + " [图](https://oss.example/a.png?Signature=abc#section)";

        assertThat(projectMarkdown(markdown)).isEqualTo(publicLinks + " [图]()");
    }

    private static Object projectMarkdown(String markdown) {
        var formal = new Formal(1L, "标题", null, markdown, List.of());
        return KnowledgeProjections.document(formal, null).fields().get("markdown");
    }
}
