package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import jakarta.servlet.http.Cookie;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.entity.UserAccountEntity;
import dev.amai.portfolio.enums.AccountStatus;
import dev.amai.portfolio.enums.AccountType;
import dev.amai.portfolio.mapper.UserAccountMapper;
import dev.amai.portfolio.service.PasswordService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class AdminSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired UserAccountMapper accounts;
    @Autowired PasswordService passwords;

    @Test
    @Transactional
    void ownerCookieNeedsCsrfForLogout() throws Exception {
        String testPassword = "test-only-admin-password-2026";
        UserAccountEntity owner = findAccount("owner");
        owner.setPasswordHash(passwords.encode(testPassword));
        accounts.updateById(owner);
        mvc.perform(delete("/api/v1/admin/session")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/admin/session"))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.details.length()").value(0));
        String body = LoginTestClient.encryptedLoginBody(mvc, json, "owner", testPassword);
        var login = mvc.perform(post("/api/v1/admin/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("portfolio_session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();

        var session = mvc.perform(get("/api/v1/admin/session").cookie(cookie))
            .andExpect(status().isOk()).andReturn();
        var match = Pattern.compile("\"csrfToken\":\"([^\"]+)\"")
            .matcher(session.getResponse().getContentAsString());
        assertThat(match.find()).isTrue();
        String csrf = match.group(1);
        mvc.perform(delete("/api/v1/admin/session").cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(delete("/api/v1/admin/session").cookie(cookie)
                .header("X-CSRF-Token", csrf))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("OK"));
        mvc.perform(get("/api/v1/admin/session").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.loggedIn").value(false));
    }

    @Test
    void invalidLoginInputUsesStableEnvelope() throws Exception {
        mvc.perform(post("/api/v1/admin/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"challengeId\":\"\",\"encryptedPassword\":\"\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.details.length()").value(3));
    }

    @Test
    void loginChallengeIsOneTimeAndPlaintextLoginIsRejected() throws Exception {
        mvc.perform(post("/api/v1/admin/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\",\"password\":\"plain-password\"}"))
            .andExpect(status().isUnprocessableEntity());

        String encryptedBody = LoginTestClient.encryptedLoginBody(mvc, json, "owner", "wrong-password");
        mvc.perform(post("/api/v1/admin/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(encryptedBody))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        mvc.perform(post("/api/v1/admin/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(encryptedBody))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @Transactional
    void normalAccountAndDisabledOwnerNeverGetAdminAccess() throws Exception {
        String testPassword = "test-only-admin-password-2026";
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserAccountEntity normal = new UserAccountEntity();
        normal.setUsername("test-normal");
        normal.setType(AccountType.NORMAL.code());
        normal.setPasswordHash(passwords.encode(testPassword));
        normal.setStatus(AccountStatus.ENABLED.code());
        normal.setCreatedAt(now);
        normal.setUpdatedAt(now);
        accounts.insert(normal);
        String normalBody = LoginTestClient.encryptedLoginBody(mvc, json, "test-normal", testPassword);
        mvc.perform(post("/api/v1/admin/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(normalBody))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/session")
                .header("Origin", "https://outside.example")
                .contentType(MediaType.APPLICATION_JSON).content(normalBody))
            .andExpect(status().isForbidden());

        UserAccountEntity owner = findAccount("owner");
        owner.setPasswordHash(passwords.encode(testPassword));
        accounts.updateById(owner);
        String ownerBody = LoginTestClient.encryptedLoginBody(mvc, json, "owner", testPassword);
        var login = mvc.perform(post("/api/v1/admin/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(ownerBody))
            .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("portfolio_session");
        assertThat(cookie).isNotNull();
        owner.setStatus(AccountStatus.DISABLED.code());
        accounts.updateById(owner);
        mvc.perform(get("/api/v1/admin/session").cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(delete("/api/v1/admin/session").cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 即使旧 Cookie 仍存在，角色变成普通访客后也不能访问管理接口。
        owner.setStatus(AccountStatus.ENABLED.code());
        owner.setType(AccountType.NORMAL.code());
        accounts.updateById(owner);
        mvc.perform(get("/api/v1/admin/session").cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(delete("/api/v1/admin/session").cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private UserAccountEntity findAccount(String username) {
        return accounts.selectOne(Wrappers.<UserAccountEntity>lambdaQuery()
            .eq(UserAccountEntity::getUsername, username));
    }
}
