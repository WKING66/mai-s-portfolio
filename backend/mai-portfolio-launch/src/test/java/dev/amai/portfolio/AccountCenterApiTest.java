package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 真实 PostgreSQL/Redis 的本人权限、CSRF 与字段契约；事务回滚，不创建 OSS 对象。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class AccountCenterApiTest extends AuthKeyTestSupport {
    private static final String PROFILE = "/api/v1/account/profile";
    private static final String AVATAR = "/api/v1/account/avatar";
    private static final String PASSWORD = "/api/v1/account/password";
    private static final String ORIGIN = "http://127.0.0.1:3000";
    private static final String TEST_PASSWORD = "account-test-password!";
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired UserAccountMapper accounts;
    @Autowired PasswordHasher passwords;

    @Test
    void anonymousCannotAccessAnyPersonalEndpoint() throws Exception {
        mvc.perform(get(PROFILE)).andExpect(status().isUnauthorized());
        mvc.perform(put(PROFILE).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get(AVATAR)).andExpect(status().isUnauthorized());
        mvc.perform(multipart(AVATAR).file("file", new byte[] {1})).andExpect(status().isUnauthorized());
        mvc.perform(put(PASSWORD).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @Transactional
    void normalAccountOwnsProfileButCannotGrantRolesOrBypassCsrf() throws Exception {
        UserAccountDO user = createNormal();
        LoginSession session = login(user);
        try {
            mvc.perform(get(PROFILE).cookie(session.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(user.getUsername()));
            mvc.perform(put(PROFILE).cookie(session.cookie()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nickname\":\"blocked\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
            String body = json.writeValueAsString(Map.of("nickname", "  个人昵称  ",
                "accountId", 99999, "roles", new String[] {"OWNER"}));
            mvc.perform(put(PROFILE).cookie(session.cookie()).header("X-CSRF-Token", session.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value("个人昵称"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
            mvc.perform(get("/api/v1/projects").param("view", "MANAGE").cookie(session.cookie()))
                .andExpect(status().isForbidden());
            assertThat(accounts.selectById(user.getId()).getType()).isEqualTo(AccountType.NORMAL.code());
            mvc.perform(put(PROFILE).cookie(session.cookie()).header("X-CSRF-Token", session.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.nickname").value(user.getUsername()));
        } finally { logout(session); }
    }

    @Test
    @Transactional
    void invalidImageAndNicknameAreRejectedWithoutChangingAccount() throws Exception {
        UserAccountDO user = createNormal();
        LoginSession session = login(user);
        try {
            mvc.perform(get(AVATAR).cookie(session.cookie())).andExpect(status().isNotFound());
            mvc.perform(multipart(AVATAR).file(new MockMultipartFile("file", "evil.svg", "image/svg+xml", "<svg/>".getBytes()))
                    .cookie(session.cookie()).header("X-CSRF-Token", session.csrf()))
                .andExpect(status().isUnprocessableEntity());
            for (String invalid : new String[] {"字".repeat(65), "nickname\ncontrol"}) {
                mvc.perform(put(PROFILE).cookie(session.cookie()).header("X-CSRF-Token", session.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nickname", invalid))))
                    .andExpect(status().isUnprocessableEntity());
            }
            assertThat(accounts.selectById(user.getId()).getPasswordHash()).isEqualTo(user.getPasswordHash());
        } finally { logout(session); }
    }

    @Test
    @Transactional
    void wrongOldPasswordIsBusinessErrorAndDoesNotLogOutValidSession() throws Exception {
        UserAccountDO user = createNormal();
        LoginSession session = login(user);
        try {
            String oldCipher = cipher(user.getUsername(), "wrong-old-password!");
            String newCipher = cipher(user.getUsername(), "new-account-password!");
            mvc.perform(put(PASSWORD).cookie(session.cookie()).header("X-CSRF-Token", session.csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "oldEncryptedPassword", oldCipher, "newEncryptedPassword", newCipher))))
                .andExpect(status().isUnprocessableEntity());
            mvc.perform(get("/api/v1/auth/session").cookie(session.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.loggedIn").value(true));
            assertThat(passwords.matches(TEST_PASSWORD, accounts.selectById(user.getId()).getPasswordHash())).isTrue();
            user.setStatus(AccountStatus.DISABLED.code());
            accounts.updateById(user);
            mvc.perform(get(PROFILE).cookie(session.cookie())).andExpect(status().isForbidden());
        } finally { logout(session); }
    }

    private UserAccountDO createNormal() {
        UserAccountDO user = new UserAccountDO();
        user.setUsername("account_" + UUID.randomUUID().toString().replace("-", ""));
        user.setPasswordHash(passwords.encode(TEST_PASSWORD));
        user.setType(AccountType.NORMAL.code());
        user.setStatus(AccountStatus.ENABLED.code());
        user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(user.getCreatedAt());
        accounts.insert(user);
        return user;
    }

    private LoginSession login(UserAccountDO user) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/session").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(LoginTestClient.encryptedLoginBody(json, user.getUsername(), TEST_PASSWORD)))
            .andExpect(status().isOk()).andReturn();
        return new LoginSession(result.getResponse().getCookie("portfolio_session"),
            json.readTree(result.getResponse().getContentAsString()).path("data").path("csrfToken").asText());
    }
    private String cipher(String username, String password) throws Exception {
        return json.readTree(LoginTestClient.encryptedLoginBody(json, username, password)).path("encryptedPassword").asText();
    }
    private void logout(LoginSession session) throws Exception {
        mvc.perform(delete("/api/v1/auth/session").cookie(session.cookie()).header("X-CSRF-Token", session.csrf()))
            .andExpect(status().isOk());
    }
    private record LoginSession(Cookie cookie, String csrf) { }
}
