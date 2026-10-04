package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.portfolio.entity.vo.AdminProfileApiVo;
import dev.amai.portfolio.portfolio.entity.vo.PublicProfileApiVo;
import dev.amai.portfolio.system.entity.vo.SessionApiVo;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class OpenApiContractTest extends AuthKeyTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired Environment environment;

    @Test
    void exposesKnife4jUiWithTheExistingOpenApiSource() throws Exception {
        mvc.perform(get("/doc.html"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(get("/v3/api-docs/swagger-config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.url").value("/v3/api-docs"));
    }

    @Test
    void documentationEnvelopesTrackTheRuntimeResponseFields() {
        var runtimeFields = Arrays.stream(R.class.getRecordComponents())
            .map(component -> component.getName()).toList();
        assertThat(Arrays.stream(SessionApiVo.class.getRecordComponents())
            .map(component -> component.getName()).toList()).isEqualTo(runtimeFields);
        assertThat(Arrays.stream(PublicProfileApiVo.class.getRecordComponents())
            .map(component -> component.getName()).toList()).isEqualTo(runtimeFields);
        assertThat(Arrays.stream(AdminProfileApiVo.class.getRecordComponents())
            .map(component -> component.getName()).toList()).isEqualTo(runtimeFields);
    }

    @Test
    void exposesSwaggerUiInTheLocalDevProfile() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    @Test
    void documentsCurrentOperationsAndTheirSecurityBoundary() throws Exception {
        byte[] body = mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        JsonNode spec = json.readTree(body);
        JsonNode scheme = spec.path("components").path("securitySchemes").path("userSession");
        assertThat(scheme.path("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asText()).isEqualTo("cookie");
        assertThat(scheme.path("name").asText()).isEqualTo(AuthConstants.SESSION_COOKIE_NAME);
        assertThat(scheme.path("name").asText())
            .isEqualTo(environment.getRequiredProperty("sa-token.token-name"));

        JsonNode session = spec.path("paths").path(AuthConstants.AUTH_SESSION_PATH);
        assertThat(spec.path("paths").has("/api/v1/admin/session")).isFalse();
        assertThat(spec.path("paths").has(AuthConstants.AUTH_SESSION_PATH + "/challenge")).isFalse();
        JsonNode profile = spec.path("paths").path("/api/v1/public/profile").path("get");
        JsonNode adminProfile = spec.path("paths").path("/api/v1/admin/profile");
        assertThat(session.path("get").path("responses").has("200")).isTrue();
        assertThat(session.path("get").path("responses").has("403")).isTrue();
        assertThat(session.path("post").path("responses").has("401")).isTrue();
        assertThat(session.path("post").path("responses").has("400")).isTrue();
        assertThat(session.path("delete").path("responses").has("401")).isTrue();
        assertThat(session.path("delete").path("responses").has("403")).isTrue();
        assertThat(profile.path("responses").has("200")).isTrue();
        assertThat(adminProfile.path("get").path("responses").has("200")).isTrue();
        assertThat(adminProfile.path("patch").path("responses").has("409")).isTrue();
        assertThat(adminProfile.path("patch").path("responses").has("422")).isTrue();
        assertThat(adminProfile.path("get").path("security").get(0).has("userSession")).isTrue();
        assertThat(adminProfile.path("patch").path("security").get(0).has("userSession")).isTrue();
        assertThat(adminProfile.path("patch").path("parameters").get(0).path("name").asText())
            .isEqualTo(AuthConstants.CSRF_HEADER_NAME);
        assertThat(adminProfile.path("patch").path("requestBody").path("content")
            .path("application/json").path("schema").isMissingNode()).isFalse();
        assertThat(session.path("post").path("requestBody").path("content")
            .path("application/json").path("schema").isMissingNode()).isFalse();
        assertThat(session.path("get").path("responses").path("200").path("content")
            .path("application/json").path("schema").isMissingNode()).isFalse();
        assertThat(profile.path("responses").path("200").path("content")
            .path("application/json").path("schema").isMissingNode()).isFalse();
        assertThat(session.path("delete").path("responses").path("403").path("content")
            .path("application/json").path("schema").isMissingNode()).isFalse();

        // 登录/会话查询和公开资料不要求 Cookie；登出只要求用户会话，不要求 OWNER。
        assertThat(spec.path("security").isMissingNode()).isTrue();
        assertThat(session.path("get").path("security").isMissingNode()).isTrue();
        assertThat(session.path("post").path("security").isMissingNode()).isTrue();
        assertThat(profile.path("security").isMissingNode()).isTrue();
        assertThat(session.path("delete").path("security").get(0).has("userSession")).isTrue();
        JsonNode csrfHeader = session.path("delete").path("parameters").get(0);
        assertThat(csrfHeader.path("name").asText()).isEqualTo(AuthConstants.CSRF_HEADER_NAME);
        assertThat(csrfHeader.path("required").asBoolean()).isTrue();

        JsonNode loginRequest = spec.path("components").path("schemas").path("LoginRequest");
        assertThat(loginRequest.path("properties").size()).isEqualTo(2);
        assertThat(loginRequest.path("properties").has("challengeId")).isFalse();
        assertThat(loginRequest.path("properties").has("password")).isFalse();
        assertThat(loginRequest.path("properties").path("encryptedPassword")
            .path("writeOnly").asBoolean()).isTrue();
        assertThat(spec.path("components").path("schemas").has("SessionVo")).isTrue();
        assertThat(spec.path("components").path("schemas").has("PublicProfileVo")).isTrue();
        assertThat(spec.path("components").path("schemas").path("SessionApiVo")
            .path("properties").path("data").path("$ref").asText())
            .isEqualTo("#/components/schemas/SessionVo");
        assertThat(spec.path("components").path("schemas").path("PublicProfileApiVo")
            .path("properties").path("data").path("$ref").asText())
            .isEqualTo("#/components/schemas/PublicProfileVo");
        assertThat(spec.path("components").path("schemas").path("AdminProfileApiVo")
            .path("properties").path("data").path("$ref").asText())
            .isEqualTo("#/components/schemas/AdminProfileVo");
    }
}
