package dev.amai.portfolio.blog.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.amai.portfolio.blog.service.BlogService;
import dev.amai.portfolio.blog.service.DocumentExportService;
import dev.amai.portfolio.blog.service.DocumentImportTaskService;
import dev.amai.portfolio.common.PageRequest;
import dev.amai.portfolio.common.PageVO;
import dev.amai.portfolio.common.ViewPageVO;
import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaginationContractTest {
    @Test
    void defaultsAndFlatParametersKeepViewAndPageResponseFields() throws Exception {
        var blogs = mock(BlogService.class);
        when(blogs.list(eq("PUBLIC"), any(PageRequest.class))).thenAnswer(invocation -> {
            PageRequest page = invocation.getArgument(1);
            return new ViewPageVO<>("PUBLIC", page.getPage(), page.sizeOrDefault(), 1, List.of());
        });
        var tasks = mock(DocumentImportTaskService.class);
        when(tasks.page(eq(7L), any(PageRequest.class))).thenAnswer(invocation -> {
            PageRequest page = invocation.getArgument(1);
            return new PageVO<>(page.getPage(), page.sizeOrDefault(), 31, List.of());
        });
        var mvc = MockMvcBuilders.standaloneSetup(
                        new BlogController(blogs, mock(DocumentExportService.class), tasks))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/blogs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.view").value("PUBLIC"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(12))
                .andExpect(jsonPath("$.data.items").isArray());
        mvc.perform(get("/api/v1/blogs/imports").param("documentId", "7").param("page", "2").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(31));
    }

    @Test
    void invalidPaginationIsRejectedBeforeBusinessQueries() throws Exception {
        var blogs = mock(BlogService.class);
        var tasks = mock(DocumentImportTaskService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                        new BlogController(blogs, mock(DocumentExportService.class), tasks))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String path : List.of("/api/v1/blogs", "/api/v1/blogs/imports")) {
            mvc.perform(get(path).param("page", "0")).andExpect(status().isUnprocessableEntity());
            mvc.perform(get(path).param("size", "51")).andExpect(status().isUnprocessableEntity());
            mvc.perform(get(path).param("page", "10001")).andExpect(status().isUnprocessableEntity());
        }
        verifyNoInteractions(blogs, tasks);
        assertThat(new PageRequest().sizeOrDefault(20)).isEqualTo(20);
    }
}
