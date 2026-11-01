package dev.amai.portfolio.blog.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.blog.component.DocumentRenderer;
import dev.amai.portfolio.blog.entity.domain.BlogPostDO;
import dev.amai.portfolio.blog.mapper.BlogPostMapper;
import dev.amai.portfolio.blog.service.DocumentService;
import dev.amai.portfolio.common.ContentListRequest;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class BlogListSearchTest {
    private final BlogPostMapper mapper = mock(BlogPostMapper.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final BlogServiceImpl service = new BlogServiceImpl(mapper, documents, null, null, null,
            mock(DocumentRenderer.class), null, null, null, null,
            mock(PlatformTransactionManager.class), mock(Validator.class));

    @Test
    void manageFiltersBeforePaginationAndReturnsFilteredTotal() {
        var input = new ContentListRequest("MANAGE", 2, 5, "DRAFT");
        input.setKeyword("  ALGO%_  ");
        when(mapper.selectListPage(any(), eq(0), eq("ALGO%_"), eq(true), eq(0), eq(1)))
                .thenAnswer(call -> ((Page<BlogPostDO>) call.getArgument(0)).setTotal(11));
        when(documents.summaries(anySet(), eq(true))).thenReturn(Map.of());

        var result = service.list(input);

        assertThat(result.getPage()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(5);
        assertThat(result.getTotal()).isEqualTo(11);
        verify(mapper).selectListPage(any(), eq(0), eq("ALGO%_"), eq(true), eq(0), eq(1));
        verify(mapper, never()).selectPage(any(), any());
    }

    @Test
    void publishedFilterUsesExistingBlogStateCode() {
        when(mapper.selectListPage(any(), eq(1), isNull(), eq(true), eq(0), eq(1)))
                .thenAnswer(call -> call.getArgument(0));
        when(documents.summaries(anySet(), eq(true))).thenReturn(Map.of());
        service.list(new ContentListRequest("MANAGE", 1, 20, "PUBLISHED"));
        verify(mapper).selectListPage(any(), eq(1), isNull(), eq(true), eq(0), eq(1));
    }

    @Test
    void unknownStatusIsRejectedBeforeQuerying() {
        assertThatThrownBy(() -> service.list(new ContentListRequest("MANAGE", 1, 20, "unknown")))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.code()).isEqualTo(ApiErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(mapper, documents);
    }

    @Test
    void publicSearchKeepsPublishedScopeAndRejectsStatusOverride() {
        var input = new ContentListRequest("PUBLIC", 1, 5, null);
        input.setKeyword("draft");
        when(mapper.selectListPage(any(), eq(1), eq("draft"), eq(false), eq(0), eq(1))).thenAnswer(call -> call.getArgument(0));
        when(documents.summaries(anySet(), eq(false))).thenReturn(Map.of());
        service.list(input);
        verify(mapper).selectListPage(any(), eq(1), eq("draft"), eq(false), eq(0), eq(1));
        assertThatThrownBy(() -> service.list(new ContentListRequest("PUBLIC", 1, 5, "DRAFT")))
                .hasMessageContaining("status");
    }

    @Test
    void defaultPublicAndBlankKeywordKeepOriginalPageDefaultsAndPath() {
        var input = new ContentListRequest();
        input.setKeyword("   ");
        when(mapper.selectListPage(any(), eq(1), isNull(), eq(false), eq(0), eq(1)))
                .thenAnswer(call -> call.getArgument(0));
        when(documents.summaries(anySet(), eq(false))).thenReturn(Map.of());
        var result = service.list(input);
        assertThat(result.getView()).isEqualTo("PUBLIC");
        assertThat(result.getSize()).isEqualTo(12);
        verify(mapper).selectListPage(any(), eq(1), isNull(), eq(false), eq(0), eq(1));
    }
}
