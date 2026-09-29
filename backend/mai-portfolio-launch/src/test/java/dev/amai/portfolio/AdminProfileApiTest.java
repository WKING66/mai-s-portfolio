package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.system.api.AuthConstants;
import jakarta.servlet.http.Cookie;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class AdminProfileApiTest {
    private static final String PROFILE_PATH = "/api/v1/admin/profile";
    private static final String ALLOWED_ORIGIN = "http://127.0.0.1:3000";

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    @Test
    void anonymousCannotReadOrChangeProfile() throws Exception {
        mvc.perform(get(PROFILE_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(patch(PROFILE_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void ownerCanEditApprovedFieldsWithoutChangingUnrelatedProfileData() throws Exception {
        OwnerSession session = login();
        JsonNode before = readProfile(session.cookie());
        Map<String, Object> edit = validEdit(before);
        edit.put("displayName", "阿霾测试");
        edit.put("githubUrl", " ");
        edit.put("email", " ");

        mvc.perform(patch(PROFILE_PATH).cookie(session.cookie())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(patch(PROFILE_PATH).cookie(session.cookie())
                .header(AuthConstants.CSRF_HEADER_NAME, session.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.displayName").value("阿霾测试"))
            .andExpect(jsonPath("$.data.githubUrl").doesNotExist())
            .andExpect(jsonPath("$.data.email").doesNotExist())
            .andExpect(jsonPath("$.data.avatarUrl").doesNotExist())
            .andExpect(jsonPath("$.data.resumeUrl").doesNotExist());
        mvc.perform(get("/api/v1/public/profile"))
            .andExpect(jsonPath("$.data.displayName").value("阿霾测试"))
            .andExpect(jsonPath("$.data.githubUrl").doesNotExist());

        JsonNode after = readProfile(session.cookie());
        assertThat(after.path("updatedAt").asText()).isNotEqualTo(before.path("updatedAt").asText());
        mvc.perform(patch(PROFILE_PATH).cookie(session.cookie())
                .header(AuthConstants.CSRF_HEADER_NAME, session.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
    }

    @Test
    @Transactional
    void badEmailAndGithubUrlAreRejectedBeforeDatabaseWrite() throws Exception {
        OwnerSession session = login();
        JsonNode before = readProfile(session.cookie());
        Map<String, Object> edit = validEdit(before);
        edit.put("email", "not-an-email");
        edit.put("githubUrl", "https://evil.example/me");

        mvc.perform(patch(PROFILE_PATH).cookie(session.cookie())
                .header(AuthConstants.CSRF_HEADER_NAME, session.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(readProfile(session.cookie()).path("updatedAt").asText())
            .isEqualTo(before.path("updatedAt").asText());

        edit.put("email", "2899964923@qq.com");
        mvc.perform(patch(PROFILE_PATH).cookie(session.cookie())
                .header(AuthConstants.CSRF_HEADER_NAME, session.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(readProfile(session.cookie()).path("updatedAt").asText())
            .isEqualTo(before.path("updatedAt").asText());

        edit.put("githubUrl", "https://github.com/WKING66");
        edit.put("displayName", " ");
        mvc.perform(patch(PROFILE_PATH).cookie(session.cookie())
                .header(AuthConstants.CSRF_HEADER_NAME, session.csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(edit)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private OwnerSession login() throws Exception {
        String body = LoginTestClient.encryptedLoginBody(mvc, json, "owner", System.getenv("OWNER_PASSWORD"));
        var response = mvc.perform(post("/api/v1/admin/session")
                .header("Origin", ALLOWED_ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn().getResponse();
        Cookie cookie = response.getCookie(AuthConstants.SESSION_COOKIE_NAME);
        assertThat(cookie).isNotNull();
        JsonNode session = json.readTree(response.getContentAsByteArray()).path("data");
        return new OwnerSession(cookie, session.path("csrfToken").asText());
    }

    private JsonNode readProfile(Cookie cookie) throws Exception {
        byte[] body = mvc.perform(get(PROFILE_PATH).cookie(cookie))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return json.readTree(body).path("data");
    }

    private Map<String, Object> validEdit(JsonNode profile) {
        Map<String, Object> edit = new HashMap<>();
        edit.put("displayName", profile.path("displayName").asText());
        edit.put("headline", profile.path("headline").asText());
        edit.put("intro", profile.path("intro").asText());
        edit.put("githubUrl", profile.path("githubUrl").asText());
        edit.put("email", profile.path("email").asText());
        edit.put("updatedAt", profile.path("updatedAt").asText());
        return edit;
    }

    private record OwnerSession(Cookie cookie, String csrf) {
    }
}
