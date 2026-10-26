package dev.amai.portfolio.blog.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.blog.component.DocumentRenderer;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.dto.PublishedBlogData;
import dev.amai.portfolio.blog.enums.ContentStage;
import dev.amai.portfolio.blog.mapper.BlogPostMapper;
import dev.amai.portfolio.blog.service.DocumentService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class BlogPublicReadTest {
    @Test
    void publishedSubjectIsReusedWithoutReadingIdentityOrDraftAgain() {
        var blogPostMapper = mock(BlogPostMapper.class);
        var documentService = mock(DocumentService.class);
        var renderer = mock(DocumentRenderer.class);
        var service = new BlogServiceImpl(blogPostMapper, documentService, null, null, null,
                renderer, null, null, null, null, mock(PlatformTransactionManager.class), mock(Validator.class));
        var article = new PublishedBlogData();
        var blog = new BlogPostDO();
        blog.setId(1L);
        blog.setDocumentId(2L);
        blog.setStatus(1);
        blog.setSlug("article");
        blog.setAllowVisitorDownload(1);
        article.setBlog(blog);
        article.setDocument(new DocumentDO());
        article.setContent(new DocumentContentDO());
        var document = new Document(2, 3, 4, ContentStage.PUBLISHED, false, true,
                "正式标题", "", "正式正文", List.of(), List.of());
        when(blogPostMapper.selectPublishedArticle("article", 1, 1)).thenReturn(article);
        when(documentService.readPublished(article.getDocument(), article.getContent())).thenReturn(document);
        when(renderer.resources(document, false)).thenReturn(List.of());

        var result = service.publicArticle("article");

        assertThat(result.content()).isEqualTo("正式正文");
        assertThat(result.hasDraft()).isNull();
        assertThat(result.revision()).isNull();
        verify(documentService).readPublished(article.getDocument(), article.getContent());
        verifyNoMoreInteractions(documentService);
        verify(blogPostMapper).selectPublishedArticle("article", 1, 1);
        verifyNoMoreInteractions(blogPostMapper);
    }

    @Test
    void missingOrUnpublishedArticleDoesNotLoadOrSignResources() {
        var blogPostMapper = mock(BlogPostMapper.class);
        var documentService = mock(DocumentService.class);
        var renderer = mock(DocumentRenderer.class);
        var service = new BlogServiceImpl(blogPostMapper, documentService, null, null, null,
                renderer, null, null, null, null, mock(PlatformTransactionManager.class), mock(Validator.class));

        assertThatThrownBy(() -> service.publicArticle("hidden"))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        verifyNoInteractions(documentService, renderer);
    }
}
