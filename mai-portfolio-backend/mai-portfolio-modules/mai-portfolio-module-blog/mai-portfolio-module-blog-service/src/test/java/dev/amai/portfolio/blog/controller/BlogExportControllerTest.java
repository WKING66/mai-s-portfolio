package dev.amai.portfolio.blog.controller;

import dev.amai.portfolio.blog.service.DocumentImportTaskService;
import dev.amai.portfolio.blog.enums.ContentStage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.domain.DocumentExport;
import dev.amai.portfolio.blog.service.BlogService;
import dev.amai.portfolio.blog.service.DocumentExportService;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

class BlogExportControllerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void responsePreservesUtf8TitleAndForwardsTheResourceSelection(boolean includeResources) {
        BlogService blogService = mock(BlogService.class);
        DocumentExportService documentExportService = mock(DocumentExportService.class);
        var document = new Document(3, 9, 1, ContentStage.DRAFT, true, false, "数据结构与算法", "", "body", List.of(), List.of());
        String filename = "数据结构与算法" + (includeResources ? ".zip" : ".md");
        var plan = new DocumentExport(filename,
                includeResources ? "application/zip" : "text/markdown;charset=UTF-8",
                output -> output.write(1));
        when(blogService.exportDocument(3, "MANAGE")).thenReturn(document);
        when(documentExportService.prepare(document, includeResources)).thenReturn(plan);

        var response = new BlogController(blogService, documentExportService, mock(DocumentImportTaskService.class)).export(3, "MANAGE", includeResources);

        String header = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(header).contains("filename*=UTF-8''");
        assertThat(ContentDisposition.parse(header).getFilename()).isEqualTo(filename);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(documentExportService).prepare(document, includeResources);
    }
}
