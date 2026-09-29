package dev.amai.portfolio;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.common.ResponseMessageConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class ProfileApiTest {
    @Autowired MockMvc mvc;

    @Test
    void publishesOnlyApprovedProfileAndRealTechTags() throws Exception {
        mvc.perform(get("/api/v1/public/profile"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value(ResponseMessageConstants.SUCCESS))
            .andExpect(jsonPath("$.data.displayName").value("阿霾"))
            .andExpect(jsonPath("$.data.githubUrl").value("https://github.com/WKING66"))
            .andExpect(jsonPath("$.data.email").value("2899964923@qq.com"))
            .andExpect(jsonPath("$.data.resumeUrl").doesNotExist())
            .andExpect(jsonPath("$.data.avatarUrl").doesNotExist())
            .andExpect(jsonPath("$.data.techStack.length()").value(21));
    }

    @Test
    void unknownApiRouteUsesTheSameErrorEnvelope() throws Exception {
        mvc.perform(get("/api/v1/public/does-not-exist"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }
}
