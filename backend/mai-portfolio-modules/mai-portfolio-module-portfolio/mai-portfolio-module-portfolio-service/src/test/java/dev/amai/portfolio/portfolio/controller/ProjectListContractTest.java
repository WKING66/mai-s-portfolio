package dev.amai.portfolio.portfolio.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import dev.amai.portfolio.portfolio.service.ProjectService;
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
}
