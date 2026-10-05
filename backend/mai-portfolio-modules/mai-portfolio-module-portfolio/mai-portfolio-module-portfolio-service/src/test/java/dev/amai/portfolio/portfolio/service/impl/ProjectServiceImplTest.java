package dev.amai.portfolio.portfolio.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import dev.amai.portfolio.portfolio.entity.domain.ProjectDO;
import dev.amai.portfolio.portfolio.entity.request.ProjectLinkRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectListRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectVersionRequest;
import dev.amai.portfolio.portfolio.mapper.ProjectLinkMapper;
import dev.amai.portfolio.portfolio.mapper.ProjectMapper;
import dev.amai.portfolio.portfolio.mapper.ProjectTagMapper;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.system.api.TechTagOptionData;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 单元测试仅补充服务层边界；数据库事务与授权由真实浏览器联调验收。 */
class ProjectServiceImplTest {
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectLinkMapper links = mock(ProjectLinkMapper.class);
    private final ProjectTagMapper tags = mock(ProjectTagMapper.class);
    private final TaxonomyQueryService taxonomy = mock(TaxonomyQueryService.class);
    private final ProjectServiceImpl service = new ProjectServiceImpl(projects, links, tags, taxonomy);

    @BeforeEach
    void catalog() {
        when(taxonomy.techTagOptions()).thenReturn(List.of(new TechTagOptionData(1L, "Java", "java", "LANGUAGE", null)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"manage", "INVALID", ""})
    void rejectsInvalidViewsBeforeQuery(String view) {
        assertThatThrownBy(() -> service.list(new ProjectListRequest(view, 1, 12, null)))
            .isInstanceOf(ApiException.class).hasMessage(ProjectMessageConstants.INVALID_QUERY);
        verifyNoInteractions(projects, links, tags);
    }

    @Test
    void rejectsOversizeAndPublicStatusFilter() {
        assertThatThrownBy(() -> service.list(new ProjectListRequest("PUBLIC", 1, 51, null)))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.list(new ProjectListRequest("PUBLIC", 1, 3, "DRAFT")))
            .isInstanceOf(ApiException.class);
        verifyNoInteractions(projects, links, tags);
    }

    @Test
    void rejectsDuplicateAndUnknownTagsBeforeWriting() {
        assertThatThrownBy(() -> service.create(input(List.of(1L, 1L), List.of())))
            .hasMessage(ProjectMessageConstants.INVALID_TAGS);
        assertThatThrownBy(() -> service.create(input(List.of(999L), List.of())))
            .hasMessage(ProjectMessageConstants.INVALID_TAGS);
        verifyNoInteractions(projects, links, tags);
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "https://user:secret@example.com", "/relative", "https://"})
    void rejectsUnsafeExternalLinks(String url) {
        assertThatThrownBy(() -> service.create(input(List.of(1L),
            List.of(new ProjectLinkRequest("CODE", "源码", url, true, 0)))))
            .hasMessage(ProjectMessageConstants.INVALID_LINKS);
        verifyNoInteractions(projects, links, tags);
    }

    @Test
    void staleVersionCannotAlterRelations() {
        ProjectDO project = new ProjectDO();
        project.setId(1L);
        project.setVersion(2L);
        when(projects.selectById(1L)).thenReturn(project);
        assertThatThrownBy(() -> service.unpublish(1L, new ProjectVersionRequest(1L)))
            .hasMessage(ProjectMessageConstants.CONFLICT);
        verifyNoInteractions(links, tags);
    }

    private ProjectRequest input(List<Long> tagIds, List<ProjectLinkRequest> inputLinks) {
        return new ProjectRequest(null, "test-project", "测试", "摘要", "贡献", null, null,
            tagIds, inputLinks, false, 0);
    }
}
