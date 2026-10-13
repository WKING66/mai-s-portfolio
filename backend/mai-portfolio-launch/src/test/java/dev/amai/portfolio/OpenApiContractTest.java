package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.system.api.AuthConstants;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {
            "spring.datasource.druid.url=jdbc:postgresql://127.0.0.1:65535/unused",
            "spring.datasource.druid.username=unused",
            "spring.datasource.druid.password=unused",
            "spring.datasource.druid.initial-size=0",
            "spring.flyway.enabled=false",
            "springdoc.api-docs.enabled=true",
            "portfolio.bootstrap.enabled=false",
            "portfolio.media.storage=local"
        })
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OpenApiContractTest extends AuthKeyTestSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.redisson.api.RedissonClient redisson;

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
    void exposesSwaggerUiInTheLocalDevProfile() throws Exception {
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    @Test
    void documentsCurrentOperationsAndTheirSecurityBoundary() throws Exception {
        byte[] body =
                mvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        JsonNode spec = json.readTree(body);
        JsonNode scheme = spec.path("components").path("securitySchemes").path("userSession");
        assertThat(scheme.path("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asText()).isEqualTo("cookie");
        assertThat(scheme.path("name").asText()).isEqualTo(AuthConstants.SESSION_COOKIE_NAME);
        assertThat(scheme.path("name").asText())
                .isEqualTo(environment.getRequiredProperty("sa-token.token-name"));

        JsonNode session = spec.path("paths").path(AuthConstants.AUTH_SESSION_PATH);
        JsonNode registration = spec.path("paths").path("/api/v1/auth/register").path("post");
        assertThat(registration.isMissingNode()).isFalse();
        assertThat(registration.path("security").isMissingNode()).isTrue();
        assertThat(registration.path("responses").has("400")).isTrue();
        assertThat(registration.path("responses").has("409")).isTrue();
        assertThat(registration.path("responses").has("422")).isTrue();
        assertThat(registration.path("responses").has("429")).isTrue();
        assertThat(spec.path("paths").has("/api/v1/auth/session/register")).isFalse();
        assertThat(spec.path("paths").has("/api/v1/admin/session")).isFalse();
        assertThat(spec.path("paths").has(AuthConstants.AUTH_SESSION_PATH + "/challenge"))
                .isFalse();
        JsonNode profileOperations = spec.path("paths").path("/api/v1/profile");
        JsonNode profile = profileOperations.path("get");
        assertThat(session.path("get").path("responses").has("200")).isTrue();
        assertThat(session.path("get").path("responses").has("403")).isTrue();
        assertThat(session.path("post").path("responses").has("401")).isTrue();
        assertThat(session.path("post").path("responses").has("400")).isTrue();
        assertThat(session.path("delete").path("responses").has("401")).isTrue();
        assertThat(session.path("delete").path("responses").has("403")).isTrue();
        assertThat(profile.path("responses").has("200")).isTrue();
        assertThat(profileOperations.path("patch").path("responses").has("409")).isTrue();
        assertThat(profileOperations.path("patch").path("responses").has("422")).isTrue();
        assertThat(profileOperations.path("patch").path("security").get(0).has("userSession"))
                .isTrue();
        assertThat(profileOperations.path("patch").path("parameters").get(0).path("name").asText())
                .isEqualTo(AuthConstants.CSRF_HEADER_NAME);
        assertThat(
                        profileOperations
                                .path("patch")
                                .path("requestBody")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .isMissingNode())
                .isFalse();
        assertThat(
                        session.path("post")
                                .path("requestBody")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .isMissingNode())
                .isFalse();
        assertThat(
                        session.path("get")
                                .path("responses")
                                .path("200")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .isMissingNode())
                .isFalse();
        assertThat(
                        profile.path("responses")
                                .path("200")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .isMissingNode())
                .isFalse();
        assertThat(
                        session.path("delete")
                                .path("responses")
                                .path("403")
                                .path("content")
                                .path("application/json")
                                .path("schema")
                                .isMissingNode())
                .isFalse();

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
        assertThat(
                        loginRequest
                                .path("properties")
                                .path("encryptedPassword")
                                .path("writeOnly")
                                .asBoolean())
                .isTrue();
        assertThat(spec.path("components").path("schemas").has("SessionVo")).isTrue();
        assertThat(spec.path("components").path("schemas").has("SiteProfileVo")).isTrue();
        assertThat(
                        spec.path("components")
                                .path("schemas")
                                .path("RSessionVo")
                                .path("properties")
                                .path("data")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/SessionVo");
        assertThat(
                        spec.path("components")
                                .path("schemas")
                                .path("RSiteProfileVo")
                                .path("properties")
                                .path("data")
                                .path("$ref")
                                .asText())
                .isEqualTo("#/components/schemas/SiteProfileVo");
        for (String removed :
                java.util.List.of(
                        "/api/v1/admin/profile",
                        "/api/v1/public/profile",
                        "/api/v1/admin/projects",
                        "/api/v1/admin/tags",
                        "/api/v1/admin/assets/images",
                        "/api/v1/public/profile/media/{id}",
                        "/api/v1/projects/{id}/cover")) {
            assertThat(spec.path("paths").has(removed)).as(removed).isFalse();
        }
        assertThat(spec.path("paths").path("/api/v1/assets").has("post")).isTrue();
        assertThat(spec.path("paths").path("/api/v1/account/avatar").has("put")).isTrue();
        assertThat(spec.path("paths").path("/api/v1/account/avatar").has("get")).isFalse();
        assertThat(spec.path("paths").path("/api/v1/account/avatar").has("post")).isFalse();
    }

    @Test
    void documentsUnifiedBlogAndAuthenticatedExport() throws Exception {
        JsonNode spec =
                json.readTree(
                        mvc.perform(get("/v3/api-docs"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray());
        var paths = spec.path("paths");
        for (String path :
                java.util.List.of(
                        "/api/v1/blogs",
                        "/api/v1/blogs/{id}",
                        "/api/v1/blogs/by-slug/{slug}",
                        "/api/v1/blogs/imports",
                        "/api/v1/blogs/{id}/publish",
                        "/api/v1/blogs/{id}/unpublish",
                        "/api/v1/blogs/{id}/export")) assertThat(paths.has(path)).as(path).isTrue();
        assertThat(
                        paths.path("/api/v1/blogs/{id}/export")
                                .path("get")
                                .path("security")
                                .get(0)
                                .has("userSession"))
                .isTrue();
        assertThat(
                        paths.path("/api/v1/blogs/by-slug/{slug}")
                                .path("get")
                                .path("security")
                                .isMissingNode())
                .isTrue();
        assertThat(
                        paths.path("/api/v1/blogs")
                                .path("post")
                                .path("parameters")
                                .get(0)
                                .path("name")
                                .asText())
                .isEqualTo(AuthConstants.CSRF_HEADER_NAME);
        assertThat(paths.has("/api/v1/blogs/{id}/images/{assetId}")).isFalse();
    }
}
