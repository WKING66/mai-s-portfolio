package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.annotation.SaCheckRole;

import com.alibaba.druid.pool.DruidDataSource;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.exception.InvalidCredentialPayloadException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;

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
@Import(BootContextSmokeTest.RoleAnnotationProbeController.class)
class BootContextSmokeTest extends AuthKeyTestSupport {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private org.redisson.api.RedissonClient redisson;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private dev.amai.portfolio.portfolio.service.ProjectService projects;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private dev.amai.portfolio.portfolio.service.SiteProfileService profiles;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private dev.amai.portfolio.blog.service.BlogService blogs;

    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mvc;

    @Test
    void bootFourContextStartsWithSelectedLibraries() {
        assertThat(dataSource).isInstanceOf(DruidDataSource.class);
        // Full PostgreSQL + Druid behavior is checked separately against the development database.
    }

    @Test
    void saTokenRoleAnnotationIsEnforcedOutsideAdminRouteInterceptor() throws Exception {
        mvc.perform(get("/test/security/owner-only"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void realAuthServicesUseDedicatedPayloadExceptionInFullApplication() throws Exception {
        // 固定对外合同，不能随生产常量一起迁移而掩盖匿名注册路由漂移。
        for (String route : java.util.List.of("/api/v1/auth/session", "/api/v1/auth/register")) {
            mvc.perform(
                            post(route)
                                    .secure(true)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIAL_PAYLOAD"))
                    .andExpect(jsonPath("$.details.length()").value(2))
                    .andExpect(
                            result ->
                                    assertThat(result.getResolvedException())
                                            .isInstanceOf(InvalidCredentialPayloadException.class));
        }
    }

    @Test
    void nestedRegistrationAndNeighboringWritesDoNotInheritAnonymousExemption() throws Exception {
        for (String route :
                java.util.List.of("/api/v1/auth/session/register", "/api/v1/auth/register/extra")) {
            mvc.perform(
                            post(route)
                                    .secure(true)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        }
    }

    @Test
    void unifiedApiKeepsPublicReadsAndProtectsWritesBeforeDtoBinding() throws Exception {
        mvc.perform(get("/api/v1/profile")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/projects")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/blogs")).andExpect(status().isOk());
        for (String protectedRead :
                java.util.List.of(
                        "/api/v1/projects/1",
                        "/api/v1/tags",
                        "/api/v1/assets/19",
                        "/api/v1/account/profile",
                        "/api/v1/blogs/1",
                        "/api/v1/blogs/1/export")) {
            mvc.perform(get(protectedRead).secure(true)).andExpect(status().isUnauthorized());
        }
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                        "/api/v1/profile")
                                .secure(true)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart("/api/v1/assets")
                                .file("file", new byte[] {1})
                                .secure(true))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/v1/account/avatar")
                                .secure(true)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blogWritesRequireLoginBeforeImportOrRequestBinding() throws Exception {
        for (String route :
                java.util.List.of(
                        "/api/v1/blogs", "/api/v1/blogs/1/publish", "/api/v1/blogs/1/unpublish")) {
            mvc.perform(
                            post(route)
                                    .secure(true)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .multipart("/api/v1/blogs/imports")
                                .file("file", new byte[] {1})
                                .secure(true))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staticBinaryProxyRoutesAreRemovedRatherThanRetainedAsAdapters() throws Exception {
        for (String deleted :
                java.util.List.of(
                        "/api/v1/admin/assets/images/19",
                        "/api/v1/projects/1/cover",
                        "/api/v1/public/profile/media/19",
                        "/api/v1/public/profile",
                        "/api/v1/admin/profile")) {
            mvc.perform(get(deleted)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/v1/account/avatar")).andExpect(status().isMethodNotAllowed());
    }

    @RestController
    @SaCheckRole(AuthConstants.OWNER_ROLE)
    static class RoleAnnotationProbeController {
        @GetMapping("/test/security/owner-only")
        R<Void> ownerOnly() {
            return R.success(null);
        }
    }
}
