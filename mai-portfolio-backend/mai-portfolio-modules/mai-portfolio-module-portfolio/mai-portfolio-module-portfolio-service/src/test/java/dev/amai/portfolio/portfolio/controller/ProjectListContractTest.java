package dev.amai.portfolio.portfolio.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.amai.portfolio.portfolio.service.ProjectService;
import dev.amai.portfolio.common.ViewPageVO;
import dev.amai.portfolio.portfolio.entity.request.ProjectListRequest;
import dev.amai.portfolio.portfolio.entity.vo.ProjectItemVo;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectListContractTest {
    @Test void repeatedViewIsRejectedBeforeBusinessCall() throws Exception {
        var projects = mock(ProjectService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectController(projects))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/projects").param("view", "PUBLIC", "MANAGE"))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(projects);
    }

    @Test
    void inheritedPaginationBindsFlatParametersAndKeepsManageDefault() throws Exception {
        var projects = mock(ProjectService.class);
        when(projects.list(any())).thenAnswer(invocation -> {
            ProjectListRequest request = invocation.getArgument(0);
            assertThat(request.getView()).isEqualTo("MANAGE");
            assertThat(request.sizeOrDefault(20)).isEqualTo(20);
            return new ViewPageVO<ProjectItemVo>("MANAGE", request.getPage(),
                    request.sizeOrDefault(20), 0, List.of());
        });
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectController(projects))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/projects").param("view", "MANAGE").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.view").value("MANAGE"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items").isArray());
        clearInvocations(projects);
        mvc.perform(get("/api/v1/projects").param("size", "51"))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(projects);
    }
}
