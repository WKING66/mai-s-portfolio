package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.blog.entity.request.BlogContentRequest;
import dev.amai.portfolio.blog.entity.request.BlogPropertiesRequest;
import dev.amai.portfolio.blog.entity.request.BlogRequest;
import dev.amai.portfolio.blog.entity.request.BlogRevisionRequest;
import dev.amai.portfolio.blog.entity.vo.BlogVo;
import dev.amai.portfolio.blog.enums.BlogAction;
import dev.amai.portfolio.blog.service.BlogService;
import dev.amai.portfolio.common.ContentListRequest;
import dev.amai.portfolio.document.api.DocumentExportService;
import dev.amai.portfolio.document.api.DocumentImportTaskService;
import dev.amai.portfolio.document.api.DocumentService;
import dev.amai.portfolio.document.enums.ResourceStatus;
import dev.amai.portfolio.knowledge.service.CachedDocumentAuthSnapshot;
import dev.amai.portfolio.knowledge.service.KnowledgeEndpointException;
import dev.amai.portfolio.knowledge.service.KnowledgeSyncService;
import dev.amai.portfolio.portfolio.entity.request.UpdateProfileRequest;
import dev.amai.portfolio.portfolio.service.PortfolioBootstrapService;
import dev.amai.portfolio.portfolio.service.SiteProfileService;
import dev.amai.portfolio.security.context.AccountContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
            "site-guide.ai-server-token=integration-knowledge-token",
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
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(
        named = "BLOG_TEST_JDBC_URL",
        matches = "jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/blog_document_test(?:_[a-z0-9_]+)?")
class BlogDocumentLifecycleTest extends AuthKeyTestSupport {
    @MockitoBean org.redisson.api.RedissonClient redis;
    @MockitoBean ObjectStorage storage;
    @MockitoBean dev.amai.portfolio.document.component.RemoteResourceFetcher remote;
    @Autowired BlogService blogs;
    @Autowired DocumentService documents;
    @Autowired DocumentExportService exports;
    @Autowired AssetFileService files;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired KnowledgeSyncService sync;
    @Autowired PortfolioBootstrapService portfolioBootstrap;
    @Autowired SiteProfileService profiles;
    @Autowired CachedDocumentAuthSnapshot authorization;
    @Autowired PlatformTransactionManager transactions;
    private final Map<String, byte[]> objects = new HashMap<>();
    private long account;
    private MockedStatic<AccountContext> identity;

    @BeforeEach
    void prepare() throws Exception {
        jdbc.execute(
                "TRUNCATE"
                    + " knowledge_source_sync_queue,site_config,blog_post,document,document_content,document_resource,document_content_tag,"
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
                        new dev.amai.portfolio.document.entity.domain.DocumentInputResource(
                                "https://example.com/a.png",
                                "image/png",
                                png(),
                                dev.amai.portfolio.document.enums.ResourceSource.REMOTE));
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
    void backfillAndReconcileRecoverLegacyAndDriftedProjections() {
        var draft = create("sync-legacy", "Legacy", "# Legacy", false);
        long id = documentId(draft);
        jdbc.update("UPDATE document_content SET stage = 1 WHERE document_id = ?", id);
        jdbc.update("UPDATE document SET publish_status = 1 WHERE id = ?", id);

        assertThat(sync.backfill(List.of(1), true).enqueued()).isEqualTo(1);
        assertThat(sequence(id)).isNull();
        assertThat(sync.backfill(List.of(1), false).enqueued()).isEqualTo(1);
        assertThat(sync.backfill(List.of(1), false).skipped()).isEqualTo(1);
        assertThat(sequence(id)).isEqualTo(1);
        jdbc.update("UPDATE document_content SET title = 'External edit' WHERE document_id = ?", id);
        assertThat(decision(id, 1).authorized()).isFalse();
        assertThat(sync.reconcile(List.of(1), List.of(id)).repaired()).isEqualTo(1);
        assertThat(sequence(id)).isEqualTo(2);
        assertThat(decision(id, 2).title()).isEqualTo("External edit");
    }

    @Test
    void profileBootstrapAndEditsSyncOnlyThePublicProjection() {
        portfolioBootstrap.initialize();
        var initial = profiles.profile();
        var first = sync.claim(List.of(2), 1).claims().getFirst();
        assertThat(first.projection().keySet())
                .containsExactlyInAnyOrder("displayName", "headline", "intro", "githubUrl", "email");
        assertThat(first.changeSeq()).isEqualTo(1);
        assertThat(authorization.validate(List.of(new CachedDocumentAuthSnapshot.Candidate(2, first.sourceId(), 1)))
                .getFirst()).satisfies(result -> {
                    assertThat(result.authorized()).isTrue();
                    assertThat(result.slug()).isNull();
                });

        var updated = profiles.updateProfile(new UpdateProfileRequest("New author", initial.headline(), initial.intro(),
                null, null, initial.updatedAt()));
        var second = sync.claim(List.of(2), 1).claims().getFirst();
        assertThat(second.changeSeq()).isEqualTo(2);
        assertThat(second.projection()).containsEntry("displayName", "New author").doesNotContainKeys("email", "githubUrl");
        profiles.updateProfile(new UpdateProfileRequest("New author", updated.headline(), updated.intro(),
                null, null, updated.updatedAt()));
        assertThat(sync.reconcile(List.of(2), null).mismatched()).isZero();
        assertThat(sync.claim(List.of(2), 1).claims()).isEmpty();
    }

    @Test
    void internalHttpReturnsRawClaimsAndChecksBearerBeforeHandling() throws Exception {
        transition(create("sync-http", "Formal", "# Formal", false), BlogAction.PUBLISH);
        var request = post("/internal/v1/knowledge/sync/claims")
                .contentType("application/json")
                .content("{\"workerId\":\"test-worker\"}");
        mvc.perform(request.header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/knowledge/sync/claims")
                        .contentType("application/json")
                        .header("Authorization", "Bearer integration-knowledge-token")
                        .content("{\"workerId\":\"test-worker\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].leaseUntil").isString())
                .andExpect(jsonPath("$.claims[0].projection.markdown").value("# Formal"));
    }

    @Test
    void syncTracksPublicationAuthorizationAndCommittedSequence() {
        var draft = create("sync-state", "First", "# First", true);
        long id = documentId(draft);
        assertThat(publication(id)).isZero();
        assertThat(sequence(id)).isNull();
        var command = new BlogRevisionRequest(draft.revision(), requestId());
        var published = blogs.transition(draft.id(), command, BlogAction.PUBLISH);
        assertThat(publication(id)).isEqualTo(1);
        assertThat(sequence(id)).isEqualTo(1);
        blogs.transition(draft.id(), command, BlogAction.PUBLISH);
        assertThat(sequence(id)).isEqualTo(1);

        var edited = edit(published, "sync-state", "Second", "# Second", true);
        assertThat(sequence(id)).isEqualTo(1);
        assertThat(decision(id, 1).authorized()).isTrue();
        assertThat(decision(id, 1).slug()).isEqualTo("sync-state");
        var leased = sync.claim(List.of(1), 1).claims().getFirst();
        assertThat(leased.projection()).containsEntry("markdown", "# First");
        assertThat(leased.projectionHash()).hasSize(64);
        var republished = transition(edited, BlogAction.PUBLISH);
        assertThat(sequence(id)).isEqualTo(2);
        assertThat(decision(id, 1).authorized()).isFalse();
        assertThat(decision(id, 2).title()).isEqualTo("Second");

        var offline = blogs.transition(republished.id(), new BlogRevisionRequest(republished.revision(),
                requestId(), null, null, 2, true), BlogAction.UNPUBLISH);
        assertThat(publication(id)).isEqualTo(2);
        assertThat(decision(id, 2).authorized()).isFalse();
        var withdrawal = sync.claim(List.of(1), 1).claims().getFirst();
        assertThat(withdrawal.action()).isEqualTo(3);
        assertThat(withdrawal.buildMode()).isEqualTo(1);
        assertThat(withdrawal.projection()).isNull();

        var restored = blogs.transition(offline.id(), new BlogRevisionRequest(offline.revision(),
                requestId(), 2, true, null, null), BlogAction.RESTORE);
        assertThat(blogs.list(new ContentListRequest("PUBLIC", 1, 12, null)).getItems()).isEmpty();
        assertThatThrownBy(() -> blogs.publicArticle("sync-state")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> blogs.exportDocument(restored.id(), "PUBLIC")).isInstanceOf(ApiException.class);
        assertThat(decision(id, 4).authorized()).isTrue();
        var privatePost = blogs.saveProperties(restored.id(), new BlogPropertiesRequest(restored.revision(),
                requestId(), "Second", "summary", "sync-state", List.of(), true, 3, true, null, null));
        assertThat(decision(id, 5).authorized()).isFalse();
        var unchanged = blogs.saveProperties(privatePost.id(), new BlogPropertiesRequest(privatePost.revision(),
                requestId(), "Second", "summary", "sync-state", List.of(), true, 3, true, null, null));
        assertThat(unchanged.revision()).isEqualTo(privatePost.revision());
        assertThat(sequence(id)).isEqualTo(5);
    }

    @Test
    void deleteOfflineEnqueuesPurgeAndUnauthorizedPublishNeverEnqueues() {
        var neverIndexed = create("private-only", "Private", "# Private", false);
        long privateDocId = documentId(neverIndexed);
        var publishedPrivate = blogs.transition(neverIndexed.id(), new BlogRevisionRequest(neverIndexed.revision(),
                requestId(), 3, true, null, null), BlogAction.PUBLISH);
        assertThat(sequence(privateDocId)).isNull();
        var offlinePrivate = transition(publishedPrivate, BlogAction.UNPUBLISH);
        assertThat(sequence(privateDocId)).isNull();
        blogs.delete(offlinePrivate.id(), new BlogRevisionRequest(offlinePrivate.revision(), requestId()));
        assertThat(sequence(privateDocId)).isNull();

        var normal = transition(create("delete-purge", "DeleteMe", "# Body", false), BlogAction.PUBLISH);
        long normalDocId = documentId(normal);
        assertThat(sequence(normalDocId)).isEqualTo(1);
        var offlineNormal = transition(normal, BlogAction.UNPUBLISH);
        assertThat(sequence(normalDocId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT action FROM knowledge_source_sync_queue WHERE source_type=1 AND source_id=?",
                Integer.class, normalDocId)).isEqualTo(2);
        // 已下架状态下调整 L2/AI 开关仍未授权，不应重复推进 withdraw 序号。
        var stillOffline = blogs.saveProperties(offlineNormal.id(), new BlogPropertiesRequest(offlineNormal.revision(),
                requestId(), offlineNormal.title(), offlineNormal.summary(), "delete-purge", List.of(), false, 2, false, null, null));
        assertThat(sequence(normalDocId)).isEqualTo(2);
        blogs.delete(stillOffline.id(), new BlogRevisionRequest(stillOffline.revision(), requestId()));
        assertThat(sequence(normalDocId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT action FROM knowledge_source_sync_queue WHERE source_type=1 AND source_id=?",
                Integer.class, normalDocId)).isEqualTo(3);
        assertThat(decision(normalDocId, 3).authorized()).isFalse();
    }

    @Test
    void claimLimitOneDoesNotStarveReadyTaskBehindInvalidOrSkippedRow() {
        var first = transition(create("claim-skip-1", "Skip1", "# Body1", false), BlogAction.PUBLISH);
        var second = transition(create("claim-skip-2", "Skip2", "# Body2", false), BlogAction.PUBLISH);
        long firstDocId = documentId(first);
        long secondDocId = documentId(second);
        // 让先到期的任务指纹失配（将被标终态），验证 limit=1 仍能当次领到后面的就绪任务。
        jdbc.update("""
                UPDATE knowledge_source_sync_queue
                SET projection_hash = decode(repeat('00', 32), 'hex'), due_at = now() - interval '10 seconds'
                WHERE source_type = 1 AND source_id = ?
                """, firstDocId);
        jdbc.update("""
                UPDATE knowledge_source_sync_queue
                SET due_at = now() - interval '5 seconds'
                WHERE source_type = 1 AND source_id = ?
                """, secondDocId);
        var claims = sync.claim(List.of(1), 1).claims();
        assertThat(claims).hasSize(1);
        assertThat(claims.getFirst().sourceId()).isEqualTo(secondDocId);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM knowledge_source_sync_queue WHERE source_type=1 AND source_id=?",
                Integer.class, firstDocId)).isEqualTo(4);
    }

    @Test
    void rollbackOfPublishedStateRollsBackQueueAndPreservesCachedAuthorization() {
        var published = transition(create("sync-rollback", "Formal", "# Formal", true), BlogAction.PUBLISH);
        long id = documentId(published);
        assertThat(decision(id, 1).authorized()).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            blogs.transition(published.id(), new BlogRevisionRequest(published.revision(), requestId()),
                    BlogAction.UNPUBLISH);
            assertThat(sequence(id)).isEqualTo(2);
            status.setRollbackOnly();
        });
        assertThat(publication(id)).isEqualTo(1);
        assertThat(sequence(id)).isEqualTo(1);
        assertThat(decision(id, 1).authorized()).isTrue();
    }


    @Test
    void concurrentFormalAuthorizationChangesEachAdvanceSequence() throws Exception {
        var published = transition(create("sync-concurrent", "Formal", "# Formal", false), BlogAction.PUBLISH);
        long id = documentId(published);
        var template = new TransactionTemplate(transactions);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var writes = java.util.stream.IntStream.of(2, 3).mapToObj(level -> executor.submit(() -> {
                assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                template.executeWithoutResult(status -> documents.changePublication(id, null,
                        new dev.amai.portfolio.document.api.DocumentPublicationOptions(level, null, null, false)));
                return level;
            })).toList();
            start.countDown();
            for (var write : writes) {
                write.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertThat(sequence(id)).isEqualTo(3);
    }

    @Test
    void v20BackfillsAllThreeStatesWithoutChangingFormalContent() {
        String schema = "v20_" + UUID.randomUUID().toString().replace("-", "");
        org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).locations("classpath:db/postgresql").target("19").load().migrate();
        jdbc.execute("INSERT INTO " + schema + """
                .document(id, owner_id, origin_type, revision, created_at, updated_at)
                VALUES (1,1,1,1,now(),now()), (2,1,1,1,now(),now()), (3,1,1,1,now(),now())
                """);
        jdbc.execute("INSERT INTO " + schema + """
                .blog_post(document_id, slug, status, allow_visitor_download, created_at, updated_at)
                VALUES (1,'unpublished',0,0,now(),now()), (2,'published',1,0,now(),now()),
                       (3,'offline',0,0,now(),now())
                """);
        jdbc.execute("INSERT INTO " + schema + """
                .document_content(document_id, stage, content, reading_time_minutes, updated_by, created_at, updated_at)
                VALUES (1,0,'draft',1,1,now(),now()), (2,1,'published body',1,1,now(),now()),
                       (3,1,'offline body',1,1,now(),now())
                """);
        org.flywaydb.core.Flyway.configure().dataSource(dataSource)
                .schemas(schema).locations("classpath:db/postgresql").load().migrate();
        assertThat(jdbc.queryForList("SELECT publish_status FROM " + schema + ".document ORDER BY id", Integer.class))
                .containsExactly(0, 1, 2);
        assertThat(jdbc.queryForList("SELECT content FROM " + schema + ".document_content ORDER BY document_id", String.class))
                .containsExactly("draft", "published body", "offline body");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'blog_post' AND column_name = 'status'
                """, Integer.class, schema)).isZero();
    }

    @Test
    void expiredTokenIsRejectedAndFifthFailureIsTerminal() {
        var published = transition(create("sync-leases", "Formal", "# Formal", true), BlogAction.PUBLISH);
        long id = documentId(published);
        var claim = sync.claim(List.of(1), 1).claims().getFirst();
        jdbc.update("UPDATE knowledge_source_sync_queue SET lease_until = now() - interval '1 second' WHERE id = ?",
                claim.queueId());
        var expired = lease(claim);
        assertThatThrownBy(() -> sync.renew(claim.queueId(), expired))
                .isInstanceOfSatisfying(KnowledgeEndpointException.class, e -> assertThat(e.status()).isEqualTo(409));
        assertThatThrownBy(() -> sync.complete(claim.queueId(), expired))
                .isInstanceOfSatisfying(KnowledgeEndpointException.class, e -> assertThat(e.status()).isEqualTo(409));
        assertThat(sync.claim(List.of(1), 1).claims()).isEmpty();
        for (int attempt = 2; attempt <= 5; attempt++) {
            jdbc.update("UPDATE knowledge_source_sync_queue SET due_at = now() - interval '1 second' WHERE id = ?",
                    claim.queueId());
            var next = sync.claim(List.of(1), 1).claims().getFirst();
            assertThat(next.attemptCount()).isEqualTo(attempt);
            assertThat(next.leaseToken()).isNotEqualTo(claim.leaseToken());
            assertThat(sync.fail(next.queueId(), lease(next), 1, false).status())
                    .isEqualTo(attempt == 5 ? "terminal" : "retryScheduled");
        }
        assertThat(sync.claim(List.of(1), 1).claims()).isEmpty();
        assertThat(sync.retry(1, id).changeSeq()).isEqualTo(1);
        var retried = sync.claim(List.of(1), 1).claims().getFirst();
        assertThat(retried.attemptCount()).isEqualTo(1);
        sync.complete(retried.queueId(), lease(retried));
        assertThat(sync.claim(List.of(1), 1).claims()).isEmpty();
    }

    private KnowledgeSyncService.Lease lease(KnowledgeSyncService.Claim claim) {
        return new KnowledgeSyncService.Lease(claim.sourceType(), claim.sourceId(), claim.changeSeq(),
                UUID.fromString(claim.leaseToken()));
    }

    private CachedDocumentAuthSnapshot.Decision decision(long id, long seq) {
        return authorization.validate(List.of(new CachedDocumentAuthSnapshot.Candidate(1, id, seq))).getFirst();
    }

    private long documentId(BlogVo blog) {
        return jdbc.queryForObject("SELECT document_id FROM blog_post WHERE id = ?", Long.class, blog.id());
    }

    private int publication(long id) {
        return jdbc.queryForObject("SELECT publish_status FROM document WHERE id = ?", Integer.class, id);
    }

    private Long sequence(long id) {
        return jdbc.query("SELECT change_seq FROM knowledge_source_sync_queue WHERE source_type=1 AND source_id=?",
                (rows, index) -> rows.getLong(1), id).stream().findFirst().orElse(null);
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
        var unpublished = transition(republished, BlogAction.UNPUBLISH);
        assertThatThrownBy(() -> blogs.publicArticle("snapshot-test"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.NOT_FOUND));

        var unpublishedDraft = edit(unpublished, "snapshot-test", "Third", "# Third", true);
        var restored = transition(unpublishedDraft, BlogAction.RESTORE);
        assertThat(restored.hasDraft()).isTrue();
        assertThat(blogs.publicArticle("snapshot-test").content()).isEqualTo("# Second");
        assertThat(blogs.get(restored.id()).content()).isEqualTo("# Third");
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
