package dev.amai.portfolio.blog.component;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.web.exception.*;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class DocumentPipelineTest {
    @Test
    void preservesMarkdownDialectAndWhitespaceWithoutResourceChanges() {
        String body = "## 原始标题 ##\r\n\r\n| A | B |\r\n|:---|---:|\r\n| ~~删除~~ | `asset://9` |\r\n\r\n"
                + "- [x] 完成\r\n- [ ] 未完成\r\n\r\n~~~typescript [demo.ts]\r\n![示例](asset://9)\r\n~~~\r\n\r\n"
                + "[普通链接](https://example.com \"标题\")\r\n\r\n自定义 :syntax: {属性}\r\n";
        assertThat(resolver.prepare(body, Map.of(), Map.of(), false, 7).content()).isEqualTo(body);
        verifyNoInteractions(files, assets, remote);
    }

    @Test
    void changesOnlyActualResourceNodesAndIsIdempotentOnLaterSaves() {
        String prefix = "| A | B |\n|---|---|\n| ~~删除~~ | 保真 |\n\n- [x] 完成\n\n```java [Demo.java]\n![a](a.png)\n```\n\n";
        var doc = resolver.prepare(prefix + "![图片](a.png \"说明\")\n", Map.of(), Map.of(), false, 7);
        assertThat(doc.content()).startsWith(prefix).contains("resource://", "\"说明\"");
        var resource = doc.resources().getFirst();
        assertThat(resolver.prepare(doc.content(), Map.of(resource.key(), resource), Map.of(), false, 7).content())
                .isEqualTo(doc.content());
    }

    @Test
    void referenceAndNestedResourceLinksChangeOnlyTheirOwnSourceRanges() {
        String body = "before\n\n[![图][image]](report.pdf)\n\n[image]: a.png \"图标题\"\n\n```md\n[code](report.pdf)\n```\n";
        String rewritten = MarkdownDestinations.rewrite(body, (uri, image) -> "resource://" + (image ? "image" : "file"));
        assertThat(rewritten).contains("[![图](resource://image \"图标题\")](resource://file)",
                "[image]: a.png \"图标题\"", "```md\n[code](report.pdf)\n```\n").startsWith("before\n\n");
    }

    @Test
    void exportDestinationRewritePreservesDialectAndDoesNotTouchCodeExamples() {
        String body = "| A |\n|---|\n| ~~x~~ |\n\n- [x] done\n\n```md\n![example](resource://keep)\n```\n\n![real](resource://keep)\n";
        var doc = new Document(1, 2, 1, ContentStage.DRAFT, true, false, "t", "", body, List.of(), List.of());
        assertThat(new DocumentRenderer(assets).markdown(doc, Map.of("keep", "resources/a.png")))
                .isEqualTo(body.replace("![real](resource://keep)", "![real](resources/a.png)"));
    }
    private final DocumentProperties limits = new DocumentProperties();
    private final AssetFileService files = mock(AssetFileService.class);
    private final AssetQueryService assets = mock(AssetQueryService.class);
    private final RemoteResourceFetcher remote = mock(RemoteResourceFetcher.class);
    private final DocumentResourceResolver resolver =
            new DocumentResourceResolver(files, assets, remote, limits);

    @Test
    void parsesUtf8BomAndHeadingButRejectsUnsupportedOrMalformedFiles() {
        var parser = new MarkdownDocumentParser(limits);
        var parsed =
                parser.parse(
                        "sample.md",
                        "\ufeff# 标题\r\n\r\n正文".getBytes(StandardCharsets.UTF_8),
                        Map.of());
        assertThat(parsed.title()).isEqualTo("标题");
        assertThat(parsed.content()).doesNotContain("\r", "\ufeff");
        for (String name : List.of("a.pdf", "a.docx", "a.md.exe"))
            assertThatThrownBy(() -> parser.parse(name, new byte[] {1}, Map.of()))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e ->
                                    assertThat(e.code())
                                            .isEqualTo(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE));
        assertThatThrownBy(() -> parser.parse("a.md", new byte[] {(byte) 0xff}, Map.of()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void codeAndOrdinaryLinksAreNotDownloadedAndDuplicateImagesShareAResource() throws Exception {
        when(remote.fetch(anyString(), anyInt(), anyLong()))
                .thenReturn(
                        new DocumentInputResource(
                                "https://example.com/a.png", "image/png", new byte[] {1}));
        when(files.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(10L);
        var doc =
                resolver.prepare(
                        "```md\n"
                                + "![example](https://example.com/fake.png)\n"
                                + "```\n"
                                + "[site](https://example.com)\n"
                                + "![one](https://example.com/a.png)\n"
                                + "![two](https://example.com/a.png)",
                        Map.of(),
                        Map.of(),
                        true,
                        7);
        assertThat(doc.resources()).hasSize(1);
        assertThat(doc.content())
                .contains("https://example.com/fake.png", "https://example.com)", "resource://");
        verify(remote, times(1)).fetch(anyString(), anyInt(), anyLong());
        assertThat(doc.newlyCreatedAssets()).containsExactly(10L);
    }

    @Test
    void missingLocalAndFailedRemoteStayVisibleInTheDraft() throws Exception {
        when(remote.fetch(anyString(), anyInt(), anyLong()))
                .thenThrow(new java.io.IOException("unavailable"));
        var doc =
                resolver.prepare(
                        "![local](images/a.png)\n"
                                + "![remote](https://example.com/a.png)\n"
                                + "[附件](report.pdf)",
                        Map.of(),
                        Map.of(),
                        true,
                        7);
        assertThat(doc.resources())
                .extracting(DocumentResource::status)
                .containsExactly(
                        ResourceStatus.INVALID,
                        ResourceStatus.FETCH_FAILED,
                        ResourceStatus.INVALID);
        assertThat(doc.resources()).allSatisfy(r -> assertThat(r.assetId()).isNull());
        assertThat(doc.content()).doesNotContain("https://example.com/a.png");
        verifyNoInteractions(files);
    }

    @Test
    void doesNotFetchWithoutRightsOrBindOtherAccountsAsset() {
        when(assets.findReadyMetadata(9L))
                .thenReturn(
                        Optional.of(
                                new AssetMetadataData(9L, AssetType.IMAGE, AssetUsage.IMAGE, 8L)));
        var doc =
                resolver.prepare(
                        "![remote](https://example.com/a.png)\n![ чужой ](asset://9)",
                        Map.of(),
                        Map.of(),
                        false,
                        7);
        assertThat(doc.resources())
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(ResourceStatus.INVALID));
        verifyNoInteractions(remote, files);
    }

    @Test
    void preservesStableReferencesButPrunesUnusedOnes() {
        var keep =
                new DocumentResource(
                        "keep",
                        ResourceType.IMAGE,
                        ResourceSource.LOCAL,
                        "a.png",
                        9L,
                        ResourceStatus.READY);
        var drop =
                new DocumentResource(
                        "drop",
                        ResourceType.ATTACHMENT,
                        ResourceSource.LOCAL,
                        "a.pdf",
                        10L,
                        ResourceStatus.READY);
        var doc =
                resolver.prepare(
                        "![a](resource://keep)",
                        Map.of("keep", keep, "drop", drop),
                        Map.of(),
                        false,
                        7);
        assertThat(doc.resources()).containsExactly(keep);
        verifyNoInteractions(files, remote, assets);
    }

    @Test
    void resourceLimitCompensatesOnlyFilesCreatedInThisOperation() {
        limits.setMaxResources(1);
        when(files.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(12L);
        var local = new DocumentInputResource("a.png", "image/png", new byte[] {1});
        assertThatThrownBy(
                        () ->
                                resolver.prepare(
                                        "![a](a.png)\n![b](b.png)",
                                        Map.of(),
                                        Map.of("a.png", local),
                                        false,
                                        7))
                .isInstanceOf(ApiException.class);
        verify(files).discardUnbound(Set.of(12L), 7L);
    }

    @Test
    void blocksLocalTraversalAndNonPublicFetchDestinations() throws Exception {
        for (String path :
                List.of("../secret", "/etc/passwd", "C:/secret", "a\\secret", "bad?.png"))
            assertThatThrownBy(() -> DocumentResourceResolver.localPath(path))
                    .isInstanceOf(ApiException.class);
        for (String path : List.of("a%2f..%2fsecret", "%2e%2e/secret", "%GG"))
            assertThatThrownBy(() -> DocumentResourceLoader.referencePath(path))
                    .isInstanceOf(ApiException.class);
        for (String ip :
                List.of(
                        "127.0.0.1",
                        "10.0.0.1",
                        "172.16.0.1",
                        "192.168.1.1",
                        "169.254.169.254",
                        "100.64.0.1",
                        "0.0.0.0",
                        "::1",
                        "fc00::1",
                        "fe80::1",
                        "2001:db8::1"))
            assertThat(RemoteResourceFetcher.publicAddress(InetAddress.getByName(ip)))
                    .as(ip)
                    .isFalse();
        assertThat(RemoteResourceFetcher.publicAddress(InetAddress.getByName("8.8.8.8"))).isTrue();
        for (String uri :
                List.of(
                        "file:///etc/passwd",
                        "https://user:pass@example.com/a.png",
                        "https://example.com:3000/a.png"))
            assertThatThrownBy(() -> RemoteResourceFetcher.validateUri(URI.create(uri)))
                    .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void exportPreservesUntrustedMarkdownWithoutRenderingHtml() {
        var renderer = new DocumentRenderer(assets);
        var doc =
                new Document(
                        1,
                        2,
                        1,
                        ContentStage.DRAFT,
                        true,
                        false,
                        "title",
                        "",
                        "<script>alert(1)</script>\n\n"
                                + "[bad](javascript:alert(1))\n\n"
                                + "![image](resource://missing)",
                        List.of(),
                        List.of());
        assertThat(renderer.markdown(doc, Map.of()))
                .isEqualTo(doc.content().replace("resource://missing", "#resource-unavailable"));
    }

    @Test
    void encodedLocalNamesMatchSidecarsAndMalformedPathsStayFailedResources() {
        when(files.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(15L);
        var local = new DocumentInputResource("images/图 a+.png", "image/png", new byte[] {1});
        var doc =
                resolver.prepare(
                        "![图](images/%E5%9B%BE%20a+.png)\n![bad](bad?.png)",
                        Map.of(), Map.of(local.path(), local), false, 7);
        assertThat(doc.resources())
                .extracting(DocumentResource::status)
                .containsExactly(ResourceStatus.READY, ResourceStatus.INVALID);
        assertThat(DocumentResourceResolver.localPath("literal%20.png"))
                .isEqualTo("literal%20.png");
    }

    @Test
    void retryingFailedRemoteResourcePreservesItsKeyAndCredentialsAreNotStored() throws Exception {
        var failed =
                new DocumentResource(
                        "retry",
                        ResourceType.IMAGE,
                        ResourceSource.REMOTE,
                        "https://example.com/a.png",
                        null,
                        ResourceStatus.FETCH_FAILED);
        when(remote.fetch(anyString(), anyInt(), anyLong()))
                .thenReturn(
                        new DocumentInputResource(
                                "a.png", "image/png", new byte[] {1}, ResourceSource.REMOTE));
        when(files.upload(any(), anyLong(), anyString(), anyString(), any())).thenReturn(18L);
        var doc =
                resolver.prepare(
                        "![a](resource://retry)", Map.of("retry", failed), Map.of(), true, 7);
        assertThat(doc.resources().getFirst().key()).isEqualTo("retry");
        assertThat(doc.resources().getFirst().status()).isEqualTo(ResourceStatus.READY);
        var credentialed =
                resolver.prepare(
                        "![a](https://user:password@example.com/a.png?token=secret&id=7)",
                        Map.of(),
                        Map.of(),
                        false,
                        7);
        assertThat(credentialed.resources().getFirst().originalUri())
                .isEqualTo("https://example.com/a.png?token=REDACTED&id=7");
    }

    @Test
    void actualFetcherRefusesLoopbackBeforeReadingBytes() throws Exception {
        try (var fetcher = new RemoteResourceFetcher(limits)) {
            assertThatThrownBy(
                            () ->
                                    fetcher.fetch(
                                            "http://127.0.0.1:80/image.png",
                                            100,
                                            System.nanoTime()
                                                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(
                                                            1)))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    void interruptedZipClosesTheAssetAndCannotBeMistakenForACompleteArchive(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var resource =
                new DocumentResource(
                        "image",
                        ResourceType.IMAGE,
                        ResourceSource.LOCAL,
                        "a.png",
                        9L,
                        ResourceStatus.READY);
        var document =
                new Document(1, 2, 1, ContentStage.DRAFT, true, false, "Title", "", "![a](resource://image)", List.of(resource), List.of());
        when(assets.findReady(Set.of(9L), AssetType.IMAGE))
                .thenReturn(
                        Map.of(
                                9L,
                                new AssetResourceData(
                                        9L,
                                        AssetType.IMAGE,
                                        AssetUsage.IMAGE,
                                        7L,
                                        "a.png",
                                        "image/png",
                                        100,
                                        "https://oss.example/a.png",
                                        java.time.Instant.now().plusSeconds(300))));
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        var broken =
                new java.io.InputStream() {
                    @Override
                    public int read() throws java.io.IOException {
                        throw new java.io.IOException("interrupted");
                    }

                    @Override
                    public void close() {
                        closed.set(true);
                    }
                };
        when(files.openReady(9L))
                .thenReturn(new AssetFileContent(broken, 100, "a.png", "image/png"));
        var exporter =
                new dev.amai.portfolio.blog.service.impl.DocumentExportServiceImpl(
                        files, new DocumentRenderer(assets));
        var archive = temp.resolve("partial.zip");
        try (var output = java.nio.file.Files.newOutputStream(archive)) {
            assertThatThrownBy(() -> exporter.prepare(document, true).writer().write(output))
                    .isInstanceOf(java.io.IOException.class);
        }
        assertThat(closed).isTrue();
        assertThatThrownBy(() -> new java.util.zip.ZipFile(archive.toFile()))
                .isInstanceOf(java.util.zip.ZipException.class);
    }
}
