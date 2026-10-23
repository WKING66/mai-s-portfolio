package dev.amai.portfolio.blog.service.impl;

import dev.amai.portfolio.blog.enums.ContentStage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.asset.api.AssetFileContent;
import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.blog.component.DocumentRenderer;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.domain.DocumentExport;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import dev.amai.portfolio.blog.enums.ResourceSource;
import dev.amai.portfolio.blog.enums.ResourceStatus;
import dev.amai.portfolio.blog.enums.ResourceType;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DocumentExportServiceImplTest {
    private final AssetFileService assetFileService = mock(AssetFileService.class);
    private final AssetQueryService assetQueryService = mock(AssetQueryService.class);
    private final Map<Long, AssetResourceData> metadata = new HashMap<>();
    private final DocumentExportServiceImpl exporter =
            new DocumentExportServiceImpl(assetFileService, new DocumentRenderer(assetQueryService));

    @BeforeEach
    void readyAssets() {
        when(assetQueryService.findReady(anySet())).thenAnswer(invocation -> {
            Set<Long> ids = invocation.getArgument(0);
            return metadata.entrySet().stream()
                    .filter(entry -> ids.contains(entry.getKey()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        });
    }

    @Test
    void textOnlyUsesTitleAndMarkdownWithoutOpeningAssets() throws Exception {
        String body = "# 正文\n\n普通文字和 [网页](https://example.test)";
        var plan = exporter.prepare(document("数据结构与算法", body, List.of()), false);
        var output = new ByteArrayOutputStream();
        plan.writer().write(output);
        assertThat(plan.filename()).isEqualTo("数据结构与算法.md");
        assertThat(plan.contentType()).startsWith("text/markdown");
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo(body);
        verifyNoInteractions(assetFileService);
    }

    @Test
    void selectedResourcesCreateTitleNamedOfflineZipAndOneFlatDirectory() throws Exception {
        var image = resource(11, "image", ResourceType.IMAGE, "示意 图+1.png", "image-bytes");
        var attachment = resource(12, "file", ResourceType.ATTACHMENT, "项目问题记录.md", "attachment-bytes");
        var doc = document("数据结构与算法",
                "![图](resource://image)\n[附件](resource://file)\n\n~~~md\n![示例](resource://image)\n~~~\n",
                List.of(image, attachment));
        var plan = exporter.prepare(doc, true);
        var entries = archive(plan);
        assertThat(plan.filename()).isEqualTo("数据结构与算法.zip");
        assertThat(plan.contentType()).isEqualTo("application/zip");
        assertThat(entries.keySet()).containsExactly("数据结构与算法.md",
                "resources/示意 图+1.png", "resources/项目问题记录.md");
        String markdown = new String(entries.get("数据结构与算法.md"), StandardCharsets.UTF_8);
        assertThat(markdown).contains("resources/" + encode("示意 图+1.png"))
                .contains("resources/" + encode("项目问题记录.md"))
                .contains("~~~md\n![示例](resource://image)\n~~~")
                .doesNotContain("https://oss.example", "Signature=", "response-content-disposition");
        assertThat(entries.get("resources/示意 图+1.png"))
                .isEqualTo("image-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(entries.get("resources/项目问题记录.md"))
                .isEqualTo("attachment-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(doc.content()).contains("resource://file");
    }

    @Test
    void flatDirectoryAvoidsDuplicateAndCaseInsensitiveNames() throws Exception {
        var first = resource(21, "a", ResourceType.ATTACHMENT, "notes/report.txt", "first");
        var second = resource(22, "b", ResourceType.ATTACHMENT, "report.txt", "second");
        var third = resource(23, "c", ResourceType.ATTACHMENT, "REPORT.TXT", "third");
        var entries = archive(exporter.prepare(document("文章",
                "[一](resource://a) [二](resource://b) [三](resource://c)", List.of(first, second, third)), true));
        assertThat(entries.keySet()).containsExactly("文章.md", "resources/report.txt",
                "resources/report (2).txt", "resources/REPORT (3).TXT");
        assertThat(entries.keySet().stream().map(name -> name.toLowerCase(Locale.ROOT)).distinct().count())
                .isEqualTo(entries.size());
        assertThat(new String(entries.get("文章.md"), StandardCharsets.UTF_8))
                .contains("resources/" + encode("report (2).txt"));
        assertThat(entries.get("resources/report (2).txt"))
                .isEqualTo("second".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void unsafeNamesCannotEscapeTheArchiveAndLiteralPercentIsEncoded() throws Exception {
        var attachment = resource(31, "file", ResourceType.ATTACHMENT, "..\\..\\a#%20?.txt", "safe");
        var entries = archive(exporter.prepare(document("../CON", "[附件](resource://file)", List.of(attachment)), true));
        assertThat(entries.keySet()).containsExactly("file-CON.md", "resources/a#%20_.txt");
        assertThat(new String(entries.get("file-CON.md"), StandardCharsets.UTF_8))
                .contains("resources/" + encode("a#%20_.txt"));
    }

    @Test
    void unavailableResourceFailsBeforeOpeningAnyAsset() {
        var missing = new DocumentResource("bad", ResourceType.ATTACHMENT, ResourceSource.LOCAL,
                "missing.txt", null, ResourceStatus.INVALID, "未找到文件");
        assertThatThrownBy(() -> exporter.prepare(document("文章", "[附件](resource://bad)", List.of(missing)), true))
                .isInstanceOf(ApiException.class).hasMessageContaining("未就绪");
        verifyNoInteractions(assetFileService);
    }

    private DocumentResource resource(long id, String key, ResourceType type, String filename, String content) {
        AssetType assetType = type == ResourceType.IMAGE ? AssetType.IMAGE : AssetType.FILE;
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        metadata.put(id, new AssetResourceData(id, assetType,
                type == ResourceType.IMAGE ? AssetUsage.IMAGE : AssetUsage.ATTACHMENT,
                7L, filename, "application/octet-stream", bytes.length,
                "https://oss.example/legacy.bin?Signature=test", Instant.now().plusSeconds(600)));
        when(assetFileService.openReady(id)).thenAnswer(invocation ->
                new AssetFileContent(new ByteArrayInputStream(bytes), bytes.length, filename, "application/octet-stream"));
        return new DocumentResource(key, type, ResourceSource.LOCAL, filename, id, ResourceStatus.READY);
    }

    private Document document(String title, String content, List<DocumentResource> resources) {
        return new Document(3, 9, 1, ContentStage.DRAFT, true, false, title, "", content, resources, List.of());
    }

    private Map<String, byte[]> archive(DocumentExport plan) throws Exception {
        var output = new ByteArrayOutputStream();
        plan.writer().write(output);
        var entries = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private String encode(String filename) {
        return URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @Test
    void bodyOnlySelectionKeepsRemoteLinksAndTitleWithoutOpeningFiles() throws Exception {
        var attachment = resource(41, "file", ResourceType.ATTACHMENT, "附件.md", "attachment");
        String signed = "https://oss.example/legacy.bin?response-content-disposition="
                + "attachment%3B%20filename%3D%22name%25E9%25A1%25B9.md%22&Signature=test%2Bvalue%2F";
        var original = metadata.get(41L);
        metadata.put(41L, new AssetResourceData(
                original.id(), original.type(), original.usage(), original.uploaderAccountId(),
                original.originalFilename(), original.contentType(), original.byteSize(), signed, original.expiresAt()));
        var plan = exporter.prepare(document("中文标题", "[附件](resource://file)", List.of(attachment)), false);
        var output = new ByteArrayOutputStream();
        plan.writer().write(output);
        assertThat(plan.filename()).isEqualTo("中文标题.md");
        assertThat(output.toString(StandardCharsets.UTF_8)).isEqualTo("[附件](" + signed + ")");
        verifyNoInteractions(assetFileService);
    }

    @Test
    void selectedResourcesWithoutAnyResourcesStillExportMarkdown() {
        assertThat(exporter.prepare(document("纯正文", "body", List.of()), true).filename())
                .isEqualTo("纯正文.md");
    }
}
