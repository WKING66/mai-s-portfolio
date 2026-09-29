package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.alibaba.druid.pool.DruidDataSource;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AuthConstants;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(properties = {
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
class BootContextSmokeTest {
    @Autowired
    private DataSource dataSource;
    @Autowired
    private MockMvc mvc;

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

    @RestController
    @SaCheckRole(AuthConstants.OWNER_ROLE)
    static class RoleAnnotationProbeController {
        @GetMapping("/test/security/owner-only")
        R<Void> ownerOnly() {
            return R.success(null);
        }
    }
}
