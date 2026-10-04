package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import jakarta.servlet.http.Cookie;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.security.password.PasswordHasher;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
class AuthSecurityTest extends AuthKeyTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired UserAccountMapper accounts;
    @Autowired PasswordHasher passwords;

    @Test
    @Transactional
    void ownerCookieNeedsCsrfForLogout() throws Exception {
        String testPassword = "test-only-admin-password-2026";
        UserAccountDO owner = findAccount("owner");
        owner.setPasswordHash(passwords.encode(testPassword));
        accounts.updateById(owner);
        mvc.perform(delete("/api/v1/auth/session")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/auth/session"))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.details.length()").value(0));
        String body = LoginTestClient.encryptedLoginBody(json, "owner", testPassword);
        var login = mvc.perform(post("/api/v1/auth/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("portfolio_session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();

        var session = mvc.perform(get("/api/v1/auth/session").cookie(cookie))
            .andExpect(status().isOk()).andReturn();
        var match = Pattern.compile("\"csrfToken\":\"([^\"]+)\"")
            .matcher(session.getResponse().getContentAsString());
        assertThat(match.find()).isTrue();
        String csrf = match.group(1);
        mvc.perform(delete("/api/v1/auth/session").cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(delete("/api/v1/auth/session").cookie(cookie)
                .header("X-CSRF-Token", "wrong-csrf"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(delete("/api/v1/auth/session").cookie(cookie)
                .header("X-CSRF-Token", csrf))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("OK"));
        mvc.perform(get("/api/v1/auth/session").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.loggedIn").value(false));
    }

    @Test
    void invalidLoginInputUsesStableEnvelope() throws Exception {
        mvc.perform(post("/api/v1/auth/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"encryptedPassword\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIAL_PAYLOAD"))
            .andExpect(jsonPath("$.details.length()").value(2));
    }

    @Test
    void plaintextLoginIsRejectedAndCiphertextDoesNotUseOneTimeState() throws Exception {
        mvc.perform(post("/api/v1/auth/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\",\"password\":\"plain-password\"}"))
            .andExpect(status().isBadRequest());

        String encryptedBody = LoginTestClient.encryptedLoginBody(json, "owner", "wrong-password");
        mvc.perform(post("/api/v1/auth/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(encryptedBody))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(encryptedBody))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }

    @Test
    @Transactional
    void normalAccountCanUseCommonSessionButCannotAccessManagement() throws Exception {
        String password = "test-only-normal-password-2026";
        UserAccountDO normal = createNormalAccount(password);
        String body = LoginTestClient.encryptedLoginBody(json, normal.getUsername(), password);
        var login = mvc.perform(post("/api/v1/auth/session")
                .header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value(normal.getUsername())).andReturn();
        Cookie cookie = login.getResponse().getCookie("portfolio_session");
        assertThat(cookie).isNotNull();
        String csrf = json.readTree(login.getResponse().getContentAsString())
            .path("data").path("csrfToken").asText();
        mvc.perform(get("/api/v1/auth/session").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.loggedIn").value(true));
        mvc.perform(get("/api/v1/admin/profile").cookie(cookie))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(patch("/api/v1/admin/profile").cookie(cookie)
                .header("X-CSRF-Token", csrf).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/auth/session").cookie(cookie))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mvc.perform(delete("/api/v1/auth/session").cookie(cookie).header("X-CSRF-Token", "wrong"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/session").header("Origin", "https://outside.example")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_INVALID"));
        mvc.perform(delete("/api/v1/auth/session").cookie(cookie).header("X-CSRF-Token", csrf))
            .andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/session").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.loggedIn").value(false));
        mvc.perform(get("/api/v1/admin/profile").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void disabledNormalAccountCannotLoginOrUseBusinessButCanLogout() throws Exception {
        String password = "test-only-normal-password-2026";
        UserAccountDO normal = createNormalAccount(password);
        String body = LoginTestClient.encryptedLoginBody(json, normal.getUsername(), password);
        var login = mvc.perform(post("/api/v1/auth/session").header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("portfolio_session");
        String csrf = json.readTree(login.getResponse().getContentAsString())
            .path("data").path("csrfToken").asText();
        normal.setStatus(AccountStatus.DISABLED.code());
        accounts.updateById(normal);
        mvc.perform(post("/api/v1/auth/session").header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/auth/session").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/profile").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/auth/session").cookie(cookie).header("X-CSRF-Token", csrf))
            .andExpect(status().isOk());
    }

    @Test
    @Transactional
    void ownerRoleRevocationOnlyRemovesManagementAccess() throws Exception {
        String password = "test-only-admin-password-2026";
        UserAccountDO owner = findAccount("owner");
        owner.setPasswordHash(passwords.encode(password));
        accounts.updateById(owner);
        String body = LoginTestClient.encryptedLoginBody(json, "owner", password);
        var login = mvc.perform(post("/api/v1/auth/session").header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("portfolio_session");
        String csrf = json.readTree(login.getResponse().getContentAsString())
            .path("data").path("csrfToken").asText();
        mvc.perform(get("/api/v1/admin/profile").cookie(cookie)).andExpect(status().isOk());
        owner.setType(AccountType.NORMAL.code());
        accounts.updateById(owner);
        mvc.perform(get("/api/v1/auth/session").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.loggedIn").value(true));
        mvc.perform(get("/api/v1/admin/profile").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/profile").cookie(cookie).header("X-CSRF-Token", csrf)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        owner.setStatus(AccountStatus.DISABLED.code());
        accounts.updateById(owner);
        mvc.perform(get("/api/v1/auth/session").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/session").header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/auth/session").cookie(cookie).header("X-CSRF-Token", csrf))
            .andExpect(status().isOk());
    }

    private UserAccountDO createNormalAccount(String password) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserAccountDO normal = new UserAccountDO();
        normal.setUsername("test-normal-" + java.util.UUID.randomUUID());
        normal.setType(AccountType.NORMAL.code());
        normal.setPasswordHash(passwords.encode(password));
        normal.setStatus(AccountStatus.ENABLED.code());
        normal.setCreatedAt(now);
        normal.setUpdatedAt(now);
        accounts.insert(normal);
        return normal;
    }

    @Test
    @Transactional
    void deletedAccountInvalidatesTheOldSession() throws Exception {
        String password = "test-only-normal-password-2026";
        UserAccountDO normal = createNormalAccount(password);
        String body = LoginTestClient.encryptedLoginBody(json, normal.getUsername(), password);
        var login = mvc.perform(post("/api/v1/auth/session").header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();
        Cookie cookie = login.getResponse().getCookie("portfolio_session");
        // 仅删除本事务新建的测试账号，结束时回滚，不删除真实用户。
        accounts.deleteById(normal.getId());
        mvc.perform(get("/api/v1/auth/session").cookie(cookie))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/auth/session").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.loggedIn").value(false));
        mvc.perform(get("/api/v1/admin/profile").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownAccountInvalidCiphertextAndMissingOriginAreRejected() throws Exception {
        String body = LoginTestClient.encryptedLoginBody(json, "missing-auth-user", "wrong-password");
        mvc.perform(post("/api/v1/auth/session").header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
        mvc.perform(post("/api/v1/auth/session")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_INVALID"));
        mvc.perform(post("/api/v1/auth/session").header("Origin", "http://127.0.0.1:3000")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"owner\",\"encryptedPassword\":\"not-base64\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIAL_PAYLOAD"));
        mvc.perform(get("/api/v1/auth/session/challenge")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/public-key")).andExpect(status().isNotFound());
    }

    private UserAccountDO findAccount(String username) {
        return accounts.selectOne(Wrappers.<UserAccountDO>lambdaQuery()
            .eq(UserAccountDO::getUsername, username));
    }
}
