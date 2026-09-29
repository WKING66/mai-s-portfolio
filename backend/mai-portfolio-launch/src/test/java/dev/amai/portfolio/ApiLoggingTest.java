package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.system.api.AuthConstants;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class ApiLoggingTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test
    void loginLogsInputAndOutputWithoutPasswordOrCsrfToken(CapturedOutput logs) throws Exception {
        String password = System.getenv("OWNER_PASSWORD");
        String body = LoginTestClient.encryptedLoginBody(mvc, json, "owner", password);
        byte[] response = mvc.perform(post(AuthConstants.ADMIN_SESSION_PATH)
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode session = json.readTree(response).path("data");
        String csrf = session.path("csrfToken").asText();

        assertThat(logs.getOut())
            .contains("API request method=POST route=/api/v1/admin/session")
            .contains("API response method=POST route=/api/v1/admin/session")
            .contains("[REDACTED]")
            .doesNotContain(password, csrf);
    }

    @Test
    void invalidOriginIsRejectedBeforeCredentialCheck() throws Exception {
        String body = LoginTestClient.encryptedLoginBody(mvc, json, "owner", System.getenv("OWNER_PASSWORD"));
        mvc.perform(post(AuthConstants.ADMIN_SESSION_PATH)
                .header("Origin", "https://outside.example")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ORIGIN_INVALID"));
    }

    @Test
    void endpointWithoutApiLogAnnotationIsNotLogged(CapturedOutput logs) throws Exception {
        mvc.perform(get("/api/v1/public/profile"))
            .andExpect(status().isOk());

        assertThat(logs.getOut())
            .doesNotContain("API request method=GET route=/api/v1/public/profile");
    }
}
