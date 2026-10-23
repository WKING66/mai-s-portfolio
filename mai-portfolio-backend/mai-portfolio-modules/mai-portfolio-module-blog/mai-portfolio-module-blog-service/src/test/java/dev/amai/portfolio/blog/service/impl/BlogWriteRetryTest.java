package dev.amai.portfolio.blog.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.blog.component.DocumentRenderer;
import dev.amai.portfolio.blog.component.DocumentResourceResolver;
import dev.amai.portfolio.blog.entity.domain.BlogPostDO;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.domain.DocumentDO;
import dev.amai.portfolio.blog.entity.request.BlogRequest;
import dev.amai.portfolio.blog.enums.ContentStage;
import dev.amai.portfolio.blog.mapper.BlogPostMapper;
import dev.amai.portfolio.blog.service.DocumentService;
import dev.amai.portfolio.security.context.AccountContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class BlogWriteRetryTest {
    @Test
    void retryOfExistingCreationKeepsStoredFingerprintAndDoesNotPrepareFilesAgain() {
        var blogPostMapper = mock(BlogPostMapper.class);
        var documentService = mock(DocumentService.class);
        var resolver = mock(DocumentResourceResolver.class);
        var renderer = mock(DocumentRenderer.class);
        var files = mock(AssetFileService.class);
        var service = new BlogServiceImpl(blogPostMapper, documentService, null, resolver,
                null, renderer, files, null, null, null, mock(PlatformTransactionManager.class));
        var request = new BlogRequest(null, "9872cfc3-65e1-4bfb-968d-09a3f0663900",
                "my-article", "中文标题", "摘要", "纯正文", List.of(1L, 2L), true, false);
        var creation = new DocumentDO();
        creation.setId(9L);
        // 前一实现生成并保存的摘要；内联后不能使同一输入被误判为内容改变。
        creation.setCreateRequestHash("a53acf81081f08732fa9d6ab6357514f09864b1a517748c324dc1fad983376f8");
        var blog = new BlogPostDO();
        blog.setId(11L);
        blog.setDocumentId(9L);
        blog.setStatus(0);
        blog.setSlug("my-article");
        blog.setAllowVisitorDownload(1);
        var document = new Document(9L, 10L, 1L, ContentStage.DRAFT, true, false,
                "中文标题", "摘要", "纯正文", List.of(), List.of());
        when(documentService.findCreation(7L, request.requestId())).thenReturn(creation);
        when(blogPostMapper.selectOne(any())).thenReturn(blog);
        when(blogPostMapper.selectById(11L)).thenReturn(blog);
        when(documentService.read(9L, true)).thenReturn(document);
        when(renderer.resources(document, true)).thenReturn(List.of());

        try (var context = mockStatic(AccountContext.class)) {
            context.when(AccountContext::currentAccount)
                    .thenReturn(new AccountContext.Account(7L, "owner", List.of("OWNER")));
            assertThat(service.save(null, request).id()).isEqualTo(11);
        }

        verifyNoInteractions(resolver, files);
        verify(documentService, never()).create(anyLong(), anyInt(), any());
    }
}
