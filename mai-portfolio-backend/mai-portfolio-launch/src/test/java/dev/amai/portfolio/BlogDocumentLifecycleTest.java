package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.blog.entity.request.BlogContentRequest;
import dev.amai.portfolio.blog.entity.request.BlogPropertiesRequest;
import dev.amai.portfolio.blog.entity.request.BlogRequest;
import dev.amai.portfolio.blog.entity.request.BlogRevisionRequest;
import dev.amai.portfolio.blog.entity.vo.BlogVo;
import dev.amai.portfolio.blog.enums.BlogAction;
import dev.amai.portfolio.blog.enums.ResourceStatus;
import dev.amai.portfolio.blog.service.*;
import dev.amai.portfolio.common.ContentListRequest;
import dev.amai.portfolio.security.context.AccountContext;
import dev.amai.portfolio.storage.*;
import dev.amai.portfolio.system.api.*;
import dev.amai.portfolio.web.exception.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

/** 只接受独立测试库，完整运行真实 Flyway/MyBatis/事务；不读取开发库。 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=test",
            "spring.datasource.druid.url=${BLOG_TEST_JDBC_URL}",
            "spring.datasource.druid.username=${BLOG_TEST_JDBC_USERNAME:postgres}",
            "spring.datasource.druid.password=${BLOG_TEST_JDBC_PASSWORD:}",
            "spring.datasource.druid.initial-size=0",
            "portfolio.redis.key-prefix=blog-document-test:",
            "portfolio.api-log.max-payload-length=2048",
            "portfolio.api-log.additional-sensitive-fields[0]=content",
            "portfolio.security.allowed-origins[0]=https://test.example",
            "portfolio.security.login-attempt-window=5m",
            "portfolio.bootstrap.enabled=false",
            "spring.flyway.enabled=true",
            "portfolio.media.storage=local"
        })
@EnabledIfEnvironmentVariable(
        named = "BLOG_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/blog_document_test(?:_[a-z0-9_]+)?")
class BlogDocumentLifecycleTest extends AuthKeyTestSupport {
    @MockitoBean org.redisson.api.RedissonClient redis;
    @MockitoBean ObjectStorage storage;
    @MockitoBean dev.amai.portfolio.blog.component.RemoteResourceFetcher remote;
    @Autowired BlogService blogs;
    @Autowired DocumentService documents;
    @Autowired DocumentExportService exports;
    @Autowired AssetFileService files;
    @Autowired JdbcTemplate jdbc;
    private final Map<String, byte[]> objects = new HashMap<>();
    private long account;
    private MockedStatic<AccountContext> identity;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.execute(
                "TRUNCATE"
                    + " blog_post,document,document_content,document_resource,document_content_tag,"
                    + "document_import_task,document_processing_task,media_asset,user_account"
                    + " CASCADE");
        account =
                jdbc.queryForObject(
                        "INSERT INTO"
                            + " user_account(username,type,password_hash,status,created_at,updated_at)"
                            + " VALUES (?,0,'unused',0,now(),now()) RETURNING id",
                        Long.class,
                        "blog-test-" + UUID.randomUUID());
        identity = bindIdentity();
        when(storage.store(any()))
                .thenAnswer(
                        call -> {
                            ObjectStorageWriteRequest r = call.getArgument(0);
                            objects.put(r.objectKey(), r.inputStream().readAllBytes());
                            return new StoredObject(r.objectKey(), r.byteSize(), "etag");
                        });
        when(storage.accessUrl(anyString()))
                .thenAnswer(
                        call ->
                                new StoredObjectAccessUrl(
                                        "https://oss.example/"
                                                + call.getArgument(0)
                                                + "?Signature=test",
                                        Instant.parse("2030-01-01T00:00:00Z")));
        when(storage.downloadUrl(anyString(), anyString()))
                .thenAnswer(call -> new StoredObjectAccessUrl(
                        "https://oss.example/" + call.getArgument(0) + "?Signature=test",
                        Instant.parse("2030-01-01T00:00:00Z")));
        when(storage.open(anyString()))
                .thenAnswer(
                        call -> {
                            byte[] bytes = objects.get(call.getArgument(0));
                            if (bytes == null) throw new IllegalStateException("missing");
                            return new StoredObjectContent(
                                    new ByteArrayInputStream(bytes), bytes.length);
                        });
        when(remote.fetch(anyString(), anyInt(), anyLong()))
                .thenReturn(
                        new dev.amai.portfolio.blog.entity.domain.DocumentInputResource(
                                "https://example.com/a.png",
                                "image/png",
                                png(),
                                dev.amai.portfolio.blog.enums.ResourceSource.REMOTE));
        doAnswer(
                        call -> {
                            objects.remove(call.getArgument(0));
                            return null;
                        })
                .when(storage)
                .delete(anyString());
    }

    @AfterEach
    void releaseIdentity() {
        if (identity != null) {
            identity.close();
        }
    }

    private MockedStatic<AccountContext> bindIdentity() {
        var mock = mockStatic(AccountContext.class);
        mock.when(AccountContext::currentAccount)
                .thenReturn(new AccountContext.Account(account, "blog-test", List.of("OWNER")));
        return mock;
    }

    private BlogVo create(String slug, String title, String body, boolean download) {
        return blogs.create(new BlogRequest(null, requestId(), slug, title, "summary", body,
                List.of(), download, false));
    }

    private BlogVo edit(BlogVo old, String slug, String title, String body, boolean download) {
        var properties = blogs.saveProperties(old.id(), new BlogPropertiesRequest(old.revision(),
                requestId(), title, "summary", slug, List.of(), download));
        return blogs.saveContent(old.id(), new BlogContentRequest(properties.revision(),
                requestId(), body, false));
    }

    private BlogVo transition(BlogVo old, BlogAction action) {
        return blogs.transition(old.id(), new BlogRevisionRequest(old.revision(), requestId()), action);
    }

    private String requestId() {
        return UUID.randomUUID().toString();
    }

    private byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    @Test
    void markdownDialectSurvivesDatabaseSavePublishAndPublicProjection() throws Exception {
        String markdown = "# 原文\r\n\r\n| 左 | 右 |\r\n| :--- | ---: |\r\n| ~~旧~~ | 新 |\r\n\r\n"
                + "- [x] 完成\r\n- [ ] 待办\r\n\r\n```java [Example.java]\r\n"
                + "// ![code](asset://999) resource://not-a-resource\r\n```\r\n\r\n"
                + "<script>untrusted()</script>\r\n\r\n`asset://999`\r\n"
                + "$a^2+b^2=c^2$ 与 $x=\\frac{-b\\pm\\sqrt{b^2-4ac}}{2a}$\r\n\r\n"
                + "$$\r\n\\int_0^1 x^2\\,dx=\\frac{1}{3}\r\n$$\r\n\r\n"
                + "$$\r\n\\sum_{k=1}^{n}k=\\frac{n(n+1)}{2}\r\n$$\r\n\r\n"
                + "$$\r\nA=\\begin{pmatrix}\r\n1 & 2 \\\\\r\n3 & 4\r\n\\end{pmatrix}\r\n$$\r\n\r\n"
                + "$$\r\n\\begin{aligned}\r\nf(x) &= x^2+2x+1 \\\\\r\n &= (x+1)^2\r\n\\end{aligned}\r\n$$\r\n";
        var draft = create("dialect-test", "原文", markdown, false);
        assertThat(draft.content()).isEqualTo(markdown);
        assertThat(draft.resources()).isEmpty();
        var published = transition(draft, BlogAction.PUBLISH);
        var publicArticle = blogs.publicArticle("dialect-test");
        assertThat(publicArticle.content()).isEqualTo(markdown);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .valueToTree(publicArticle).has("html")).isFalse();
        assertThat(blogs.publicArticle("dialect-test").content()).isEqualTo(markdown);
    }

    @Test
    void draftAndPublishedSnapshotsStayIndependentAndStaleWritesConflict() {
        var draft = create("snapshot-test", "First", "# First", true);
        assertThat(blogs.list(new ContentListRequest("PUBLIC", 1, 12, null)).getItems()).noneMatch(b -> b.id() == draft.id());
        var published = transition(draft, BlogAction.PUBLISH);
        var edited =
                edit(published, "snapshot-test", "Second", "# Second", true);
        assertThat(blogs.publicArticle("snapshot-test").title()).isEqualTo("First");
        assertThat(blogs.publicArticle("snapshot-test").content()).isEqualTo("# First");
        assertThat(blogs.get(edited.id()).content()).isEqualTo("# Second");
        assertThat(edited.title()).isEqualTo("Second");
        assertThatThrownBy(
                        () ->
                                edit(published, "snapshot-test", "Stale", "stale", true))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.DOCUMENT_REVISION_CONFLICT));
        var republished = transition(edited, BlogAction.PUBLISH);
        assertThat(blogs.publicArticle("snapshot-test").title()).isEqualTo("Second");
        assertThat(blogs.publicArticle("snapshot-test").content()).isEqualTo("# Second");
        assertThatThrownBy(
                        () ->
                                edit(republished, "renamed", "Second", "# Second", true))
                .isInstanceOf(ApiException.class);
        transition(republished, BlogAction.UNPUBLISH);
        assertThatThrownBy(() -> blogs.publicArticle("snapshot-test"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }

    @Test
    void unresolvedImportSavesDraftButCannotPublishAndDoesNotStoreOriginalFile() {
        var draft =
                blogs.importDocument(
                        new MockMultipartFile(
                                "file",
                                "article.md",
                                "text/markdown",
                                "# Imported\n![missing](missing.png)"
                                        .getBytes(StandardCharsets.UTF_8)),
                        List.of(),
                        false, List.of(), requestId());
        assertThat(draft.resources()).hasSize(1);
        assertThat(draft.resources().getFirst().status()).isEqualTo(ResourceStatus.INVALID);
        var edited =
                edit(draft, "missing-resource", "Imported", draft.content(), true);
        assertThatThrownBy(() -> transition(edited, BlogAction.PUBLISH))
                .isInstanceOf(ApiException.class);
        assertThat(blogs.get(draft.id()).revision()).isEqualTo(edited.revision());
        assertThat(objects).isEmpty();
    }

    @Test
    void importedImagesAndAttachmentsShareAssetsAndZipContainsOfflineRelativeFiles()
            throws Exception {
        var draft =
                blogs.importDocument(
                        new MockMultipartFile(
                                "file",
                                "article.md",
                                "text/markdown",
                                "# Resources\n![图](images/a.png)\n[附件](report.txt)"
                                        .getBytes(StandardCharsets.UTF_8)),
                        List.of(
                                new MockMultipartFile(
                                        "resources", "images/a.png", "image/png", png()),
                                new MockMultipartFile(
                                        "resources",
                                        "report.txt",
                                        "text/plain",
                                        "attachment".getBytes(StandardCharsets.UTF_8))),
                        false, List.of(), requestId());
        assertThat(draft.resources())
                .hasSize(2)
                .allSatisfy(r -> assertThat(r.status()).isEqualTo(ResourceStatus.READY));
        assertThat(objects).hasSize(2);
        assertThat(draft.content()).contains("resource://").doesNotContain("oss.example");
        assertThat(draft.resources()).allSatisfy(r -> assertThat(r.resourceUrl()).startsWith("https://oss.example/"));
        var doc = blogs.exportDocument(draft.id(), "MANAGE");
        var plan = exports.prepare(doc, true);
        assertThat(plan.filename()).endsWith(".zip");
        var output = new ByteArrayOutputStream();
        plan.writer().write(output);
        Map<String, byte[]> entries = new HashMap<>();
        try (var zip =
                new ZipInputStream(
                        new ByteArrayInputStream(output.toByteArray()), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry e;
            while ((e = zip.getNextEntry()) != null) entries.put(e.getName(), zip.readAllBytes());
        }
        assertThat(entries).hasSize(3);
        String md =
                new String(
                        entries.entrySet().stream()
                                .filter(e -> e.getKey().endsWith(".md"))
                                .findFirst()
                                .orElseThrow()
                                .getValue(),
                        StandardCharsets.UTF_8);
        assertThat(md).contains("resources/").doesNotContain("https://oss.example", "resource://");
        assertThat(
                        entries.entrySet().stream()
                                .filter(e -> e.getKey().endsWith("report.txt"))
                                .findFirst()
                                .orElseThrow()
                                .getValue())
                .isEqualTo("attachment".getBytes(StandardCharsets.UTF_8));
        var bodyOnly = exports.prepare(doc, false);
        assertThat(bodyOnly.filename()).endsWith(".md");
        output.reset();
        bodyOnly.writer().write(output);
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("https://oss.example/");
        var edited = edit(draft, "resource-snapshot", "Resources", draft.content(), true);
        var published = transition(edited, BlogAction.PUBLISH);
        var privateDraft = edit(published, "resource-snapshot", "Private draft", "# No resources", true);
        assertThat(privateDraft.resources()).isEmpty();
        var publicArticle = blogs.publicArticle("resource-snapshot");
        assertThat(publicArticle.content()).isEqualTo(edited.content()).doesNotContain("Signature=");
        assertThat(publicArticle.resources()).hasSize(2);
        transition(privateDraft, BlogAction.UNPUBLISH);
        clearInvocations(storage);
        assertThatThrownBy(() -> blogs.publicArticle("resource-snapshot"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        verify(storage, never()).accessUrl(anyString());
    }

    @Test
    void bodyOnlyStillReturnsMarkdownWhenIncludeResourcesSelectedAndDownloadPolicyIsEnforced()
            throws Exception {
        var draft = create("no-resource", "Text", "body", false);
        assertThat(exports.prepare(blogs.exportDocument(draft.id(), "MANAGE"), true).filename())
                .endsWith(".md");
        assertThatThrownBy(() -> blogs.exportDocument(draft.id(), "PUBLIC"))
                .isInstanceOf(ApiException.class);
        var published = transition(draft, BlogAction.PUBLISH);
        assertThatThrownBy(() -> blogs.exportDocument(draft.id(), "PUBLIC"))
                .isInstanceOf(ApiException.class);
        var edited = edit(published, "no-resource", "Text", "body", true);
        assertThat(blogs.exportDocument(edited.id(), "PUBLIC").content()).contains("body");

        assertThat(blogs.exportDocument(draft.id(), "MANAGE").content()).contains("body");
    }

    @Test
    void duplicateSlugLeavesBoundAssetsAndDocumentsIntact() throws Exception {
        var existing = create("unique-slug", "Existing", "body", true);
        long before = jdbc.queryForObject("SELECT count(*) FROM document", Long.class);
        var imported =
                blogs.importDocument(
                        new MockMultipartFile(
                                "file",
                                "one.md",
                                "text/markdown",
                                "![a](a.png)".getBytes(StandardCharsets.UTF_8)),
                        List.of(new MockMultipartFile("resources", "a.png", "image/png", png())),
                        false, List.of(), requestId());
        Long bound = imported.resources().getFirst().assetId();
        files.discardUnbound(Set.of(bound), account);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM media_asset WHERE id=?", Long.class, bound))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                blogs.create(new BlogRequest(null, requestId(), "unique-slug",
                                                "Duplicate",
                                                "summary",
                                                "![new](https://example.com/a.png)",
                                                List.of(), true, true)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo(ApiErrorCode.BLOG_SLUG_CONFLICT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document", Long.class))
                .isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_asset", Long.class))
                .isEqualTo(1);
        assertThat(objects).hasSize(1);
    }

    @Test
    void competingEditorsCannotBothWriteTheSameRevision() throws Exception {
        var draft = create("concurrent-edit", "Original", "body", true);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> write =
                    () -> {
                        start.await();
                        try (var threadIdentity = bindIdentity()) {
                            blogs.saveContent(draft.id(), new BlogContentRequest(draft.revision(),
                                    requestId(), "new body", false));
                            return true;
                        } catch (ApiException e) {
                            assertThat(e.code()).isEqualTo(ApiErrorCode.DOCUMENT_REVISION_CONFLICT);
                            return false;
                        }
                    };
            var first = executor.submit(write);
            var second = executor.submit(write);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT revision FROM document WHERE id=?",
                                Long.class,
                                draft.documentId()))
                .isEqualTo(draft.revision() + 1);
    }

    @Test
    void signerFailureDoesNotTurnCommittedDraftIntoASaveFailure() throws Exception {
        when(storage.accessUrl(anyString()))
                .thenThrow(
                        new dev.amai.portfolio.storage.exception.ObjectStorageException(
                                "signer unavailable"));
        var draft =
                blogs.importDocument(
                        new MockMultipartFile(
                                "file",
                                "preview.md",
                                "text/markdown",
                                "![a](a.png)".getBytes(StandardCharsets.UTF_8)),
                        List.of(new MockMultipartFile("resources", "a.png", "image/png", png())),
                        false, List.of(), requestId());
        assertThat(draft.previewIssue()).isNotBlank();
        assertThat(draft.content()).contains("resource://");
        assertThat(draft.resources().getFirst().status()).isEqualTo(ResourceStatus.READY);
        assertThat(blogs.get(draft.id()).revision()).isEqualTo(draft.revision());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM blog_post", Long.class)).isEqualTo(1);
        assertThatThrownBy(() -> exports.prepare(blogs.exportDocument(draft.id(), "MANAGE"), true))
                .isInstanceOf(ApiException.class);
    }
}
