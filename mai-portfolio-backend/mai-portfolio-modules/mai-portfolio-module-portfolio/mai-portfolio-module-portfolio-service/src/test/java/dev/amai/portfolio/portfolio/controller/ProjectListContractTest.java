package dev.amai.portfolio.portfolio.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.common.ContentListRequest;
import dev.amai.portfolio.common.ViewPageVO;
import dev.amai.portfolio.portfolio.entity.vo.ProjectItemVo;
import dev.amai.portfolio.portfolio.service.ProjectService;
import dev.amai.portfolio.web.advice.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ProjectListContractTest {
    @Test
    void inheritedPaginationBindsFlatParametersAndKeepsManageDefault() throws Exception {
        var projects = mock(ProjectService.class);
        when(projects.list(any())).thenAnswer(invocation -> {
            ContentListRequest request = invocation.getArgument(0);
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
    }

    @ParameterizedTest
    @CsvSource({
        "view, manage",
        "view, INVALID",
        "view, ''",
        "page, 0",
        "page, 10001",
        "size, 0",
        "size, 51"
    })
    void rejectsInvalidListParametersBeforeCallingProjectService(String parameter, String value) throws Exception {
        var projectService = mock(ProjectService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectController(projectService))
                .setControllerAdvice(new GlobalExceptionHandler()).build();

        mvc.perform(get("/api/v1/projects").param(parameter, value))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(projectService);
    }
}
