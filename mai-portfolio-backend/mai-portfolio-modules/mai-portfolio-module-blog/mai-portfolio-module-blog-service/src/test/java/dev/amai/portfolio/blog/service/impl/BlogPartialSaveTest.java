package dev.amai.portfolio.blog.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.blog.component.DocumentRenderer;
import dev.amai.portfolio.blog.component.DocumentResourceResolver;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.request.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.blog.mapper.BlogPostMapper;
import dev.amai.portfolio.blog.service.DocumentService;
import dev.amai.portfolio.security.context.AccountContext;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class BlogPartialSaveTest {
    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();
    private final BlogPostMapper blogPostMapper = mock(BlogPostMapper.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final DocumentResourceResolver resolver = mock(DocumentResourceResolver.class);
    private final DocumentRenderer renderer = mock(DocumentRenderer.class);
    private final AssetFileService files = mock(AssetFileService.class);
    private final TaxonomyQueryService tags = mock(TaxonomyQueryService.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final BlogServiceImpl service = new BlogServiceImpl(blogPostMapper, documentService,
            null, resolver, null, renderer, files, null, tags, null, transactions, VALIDATION.getValidator());
    private final BlogPostDO blog = new BlogPostDO();
    private Document current;

    @BeforeAll
    static void initializeMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "partial-save"), BlogPostDO.class);
    }

    @BeforeEach
    void setUp() {
        blog.setId(11L);
        blog.setDocumentId(9L);
        blog.setSlug("Legacy.Slug");
        blog.setStatus(0);
        blog.setAllowVisitorDownload(1);
        current = document("stored", List.of(), true, false);
        when(blogPostMapper.selectById(11L)).thenReturn(blog);
        when(documentService.read(9, true)).thenAnswer(call -> current);
        when(documentService.references(9)).thenReturn(Map.of());
        when(documentService.beginWrite(eq(9L), anyLong(), anyString(), anyString())).thenReturn(true);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(renderer.resources(any(), eq(true))).thenReturn(List.of());
    }

    @AfterAll
    static void closeValidation() {
        VALIDATION.close();
    }

    @Test
    void unchangedBodyDoesNotParseWriteOrValidateLegacySlug() {
        try (var ignored = owner()) {
            assertThat(service.saveContent(11, body("stored")).revision()).isEqualTo(5);
        }
        verifyNoInteractions(resolver, files, tags);
        verify(documentService, never()).references(anyLong());
        verify(documentService, never()).saveContent(any(), any(), anyLong());
        verify(documentService, never()).recordWrite(anyLong(), anyString(), anyString());
        verify(blogPostMapper, never()).update(any(), any());
        verify(blogPostMapper, never()).existsOtherSlug(anyString(), any());
    }

    @Test
    void changedBodyOnlyWritesContentAndKeepsLegacySlugOutOfValidation() {
        var prepared = new PreparedDocument("changed", List.of(), Set.of());
        when(resolver.prepare(eq("changed"), anyMap(), anyMap(), eq(false), eq(7L))).thenReturn(prepared);
        try (var ignored = owner()) {
            service.saveContent(11, body("changed"));
        }
        verify(documentService).saveContent(current, prepared, 7);
        verify(documentService, never()).saveProperties(any(), any(), any(), anyList(), anyLong());
        verify(blogPostMapper, never()).existsOtherSlug(anyString(), any());
        verifyNoInteractions(tags, files);
    }

    @Test
    void unchangedPropertiesDoNotCreateDraftAdvanceRevisionOrReadResources() {
        try (var ignored = owner()) {
            assertThat(service.saveProperties(11, properties("Legacy.Slug", "Before", true)).revision())
                    .isEqualTo(5);
        }
        verifyNoInteractions(resolver, files, tags);
        verify(documentService, never()).saveProperties(any(), any(), any(), anyList(), anyLong());
        verify(documentService, never()).recordWrite(anyLong(), anyString(), anyString());
        verify(blogPostMapper, never()).update(any(), any());
        verify(blogPostMapper, never()).existsOtherSlug(anyString(), any());
    }

    @Test
    void propertyEditPreservesUnchangedLegacySlugAndNeverPreparesFiles() {
        try (var ignored = owner()) {
            service.saveProperties(11, properties("Legacy.Slug", "New title", true));
        }
        verify(documentService).saveProperties(current, "New title", "summary", List.of(), 7);
        verify(blogPostMapper, never()).existsOtherSlug(anyString(), any());
        verify(documentService, never()).saveContent(any(), any(), anyLong());
        verifyNoInteractions(resolver, files, tags);
    }

    @Test
    void downloadOnlyChangeDoesNotCreateDraftFromPublishedContent() {
        current = document("stored", List.of(), false, true);
        try (var ignored = owner()) {
            service.saveProperties(11, properties("Legacy.Slug", "Before", false));
        }
        verify(documentService, never()).saveProperties(any(), any(), any(), anyList(), anyLong());
        verify(documentService).recordWrite(eq(9L), eq("request"), anyString());
        verifyNoInteractions(resolver, files, tags);
    }

    @Test
    void changedSlugHasDedicatedFormatCode() {
        try (var ignored = owner()) {
            assertCode(() -> service.saveProperties(11, properties("Wrong Slug", "Before", true)),
                    ApiErrorCode.BLOG_SLUG_INVALID);
        }
        verify(blogPostMapper, never()).existsOtherSlug(anyString(), any());
        verify(documentService, never()).recordWrite(anyLong(), anyString(), anyString());
    }

    @Test
    void occupiedSlugExcludesCurrentArticleAndHasDedicatedCode() {
        when(blogPostMapper.existsOtherSlug("taken", 11L)).thenReturn(true);
        try (var ignored = owner()) {
            assertCode(() -> service.saveProperties(11, properties("taken", "Before", true)),
                    ApiErrorCode.BLOG_SLUG_CONFLICT);
        }
        verify(blogPostMapper).existsOtherSlug("taken", 11L);
        verify(documentService, never()).saveProperties(any(), any(), any(), anyList(), anyLong());
    }

    @Test
    void publishedSlugRemainsImmutableWhileOtherPropertiesRemainEditable() {
        current = document("stored", List.of(), true, true);
        try (var ignored = owner()) {
            assertCode(() -> service.saveProperties(11, properties("changed", "Before", true)),
                    ApiErrorCode.BLOG_SLUG_IMMUTABLE);
        }
        verifyNoInteractions(resolver, files, tags);
    }

    @Test
    void staleBodyNeverUploadsBeforeRevisionIsChecked() {
        when(documentService.beginWrite(eq(9L), eq(4L), anyString(), anyString()))
                .thenThrow(new ApiException(ApiErrorCode.DOCUMENT_REVISION_CONFLICT, "stale"));
        try (var ignored = owner()) {
            assertCode(() -> service.saveContent(11, new BlogContentRequest(4L, "request", "changed", false)),
                    ApiErrorCode.DOCUMENT_REVISION_CONFLICT);
        }
        verifyNoInteractions(resolver, files);
    }

    @Test
    void revisionChangeDuringPreparationCompensatesOnlyNewAssets() {
        var prepared = new PreparedDocument("changed", List.of(), Set.of(99L));
        when(resolver.prepare(eq("changed"), anyMap(), anyMap(), eq(false), eq(7L))).thenReturn(prepared);
        when(documentService.beginWrite(eq(9L), eq(5L), anyString(), anyString()))
                .thenThrow(new ApiException(ApiErrorCode.DOCUMENT_REVISION_CONFLICT, "stale"));
        try (var ignored = owner()) {
            assertCode(() -> service.saveContent(11, body("changed")), ApiErrorCode.DOCUMENT_REVISION_CONFLICT);
        }
        verify(files).discardUnbound(Set.of(99L), 7);
        verify(documentService, never()).saveContent(any(), any(), anyLong());
    }

    @Test
    void explicitRetryOfFailedRemoteResourceIsNotSkippedAsUnchangedBody() {
        var failed = new DocumentResource("remote", ResourceType.IMAGE, ResourceSource.REMOTE,
                "https://example.test/image.png", null, ResourceStatus.FETCH_FAILED, "timeout");
        current = document("![image](resource://remote)", List.of(failed), true, false);
        var prepared = new PreparedDocument(current.content(), current.resources(), Set.of());
        when(resolver.prepare(eq(current.content()), anyMap(), anyMap(), eq(true), eq(7L))).thenReturn(prepared);
        try (var ignored = owner()) {
            service.saveContent(11, new BlogContentRequest(5L, "request", current.content(), true));
        }
        verify(resolver).prepare(eq(current.content()), anyMap(), anyMap(), eq(true), eq(7L));
    }

    @Test
    void sameRequestIdAcrossBodyAndPropertiesUsesDifferentOperationFingerprints() {
        var hash = ArgumentCaptor.forClass(String.class);
        try (var ignored = owner()) {
            service.saveContent(11, body("stored"));
            service.saveProperties(11, properties("Legacy.Slug", "Before", true));
        }
        verify(documentService, times(2)).beginWrite(eq(9L), eq(5L), eq("request"), hash.capture());
        assertThat(hash.getAllValues().getFirst()).isNotEqualTo(hash.getAllValues().getLast());
    }

    @Test
    void committedBodyRetryDoesNotPrepareFilesOrWriteAgain() {
        when(documentService.beginWrite(eq(9L), eq(4L), anyString(), anyString())).thenReturn(false);
        try (var ignored = owner()) {
            assertThat(service.saveContent(11, new BlogContentRequest(4L, "request", "changed", false)).revision())
                    .isEqualTo(5);
        }
        verifyNoInteractions(resolver, files);
        verify(documentService, never()).saveContent(any(), any(), anyLong());
        verify(documentService, never()).recordWrite(anyLong(), anyString(), anyString());
    }

    @Test
    void requestIdConflictDoesNotPrepareBodyOrModifyProperties() {
        when(documentService.beginWrite(eq(9L), eq(5L), anyString(), anyString()))
                .thenThrow(new ApiException(ApiErrorCode.REQUEST_ID_CONFLICT, "reused"));
        try (var ignored = owner()) {
            assertCode(() -> service.saveProperties(11, properties("Legacy.Slug", "Changed", true)),
                    ApiErrorCode.REQUEST_ID_CONFLICT);
        }
        verifyNoInteractions(resolver, files, tags);
        verify(documentService, never()).saveProperties(any(), any(), any(), anyList(), anyLong());
        verify(blogPostMapper, never()).update(any(), any());
    }

    private BlogContentRequest body(String content) {
        return new BlogContentRequest(5L, "request", content, false);
    }

    private BlogPropertiesRequest properties(String slug, String title, boolean download) {
        return new BlogPropertiesRequest(5L, "request", title, "summary", slug, List.of(), download);
    }

    private Document document(String content, List<DocumentResource> resources, boolean draft, boolean published) {
        return new Document(9, 10, 5, draft ? ContentStage.DRAFT : ContentStage.PUBLISHED,
                draft, published, "Before", "summary", content, resources, List.of());
    }

    private MockedStatic<AccountContext> owner() {
        var context = mockStatic(AccountContext.class);
        context.when(AccountContext::currentAccount)
                .thenReturn(new AccountContext.Account(7L, "owner", List.of("OWNER")));
        return context;
    }

    private void assertCode(Runnable operation, ApiErrorCode code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
}
