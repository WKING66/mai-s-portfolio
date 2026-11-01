package dev.amai.portfolio.blog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.amai.portfolio.blog.service.BlogService;
import dev.amai.portfolio.blog.service.DocumentExportService;
import dev.amai.portfolio.blog.service.DocumentImportTaskService;
import dev.amai.portfolio.common.ContentListRequest;
import dev.amai.portfolio.common.ViewPageVO;
import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BlogSearchContractTest {
    @Test
    void flatFiltersUseSharedPaginationAndTrimKeyword() throws Exception {
        var service = mock(BlogService.class);
        when(service.list(any())).thenAnswer(call -> {
            ContentListRequest input = call.getArgument(0);
            assertThat(input.getKeyword()).isEqualTo("算法%_");
            assertThat(input.getStatus()).isEqualTo("DRAFT");
            return new ViewPageVO<>("MANAGE", input.getPage(), input.sizeOrDefault(), 7, List.of());
        });
        var mvc = MockMvcBuilders.standaloneSetup(
                        new BlogController(service, mock(DocumentExportService.class), mock(DocumentImportTaskService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/blogs").param("view", "MANAGE").param("status", "DRAFT")
                        .param("keyword", "  算法%_  ").param("size", "5").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(7))
                .andExpect(jsonPath("$.data.size").value(5)).andExpect(jsonPath("$.data.page").value(2));
    }

    @Test
    void oversizedKeywordReportsItsFieldAndLimit() throws Exception {
        var service = mock(BlogService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                        new BlogController(service, mock(DocumentExportService.class), mock(DocumentImportTaskService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/blogs").param("keyword", "x".repeat(51)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("keyword: 搜索关键词不能超过 50 个字符"));
        verifyNoInteractions(service);
    }
    @Test
    void invalidViewAndStatusAreRejectedByDtoBeforeService() throws Exception {
        var service = mock(BlogService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new BlogController(service,
                        mock(DocumentExportService.class), mock(DocumentImportTaskService.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/blogs").param("view", "unknown"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("view: view 仅支持 PUBLIC（公开列表）或 MANAGE（管理列表）"));
        mvc.perform(get("/api/v1/blogs").param("view", "MANAGE").param("status", "unknown"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("status: status 仅支持 DRAFT（未发布）或 PUBLISHED（已发布）"));
        verifyNoInteractions(service);
    }
}
