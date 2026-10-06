package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import java.util.Locale;
import java.util.UUID;
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

/** 注册真实数据库契约；账号仅在回滚事务内产生，会话显式注销。 */
// main 的 application.yml 与测试 application.yaml 同时存在；接口契约测试显式覆盖生产限流额度。
@SpringBootTest(properties = "portfolio.security.max-registration-attempts-per-client=256")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class RegistrationApiTest extends AuthKeyTestSupport {
    private static final String PATH = "/api/v1/auth/register";
    private static final String ORIGIN = "http://127.0.0.1:3000";
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired UserAccountMapper accounts;
    @Autowired PasswordHasher passwords;

    @Test
    @Transactional
    void registrationDoesNotLoginAndNormalLoginCannotEnterManagement() throws Exception {
        String name = uniqueName();
        String password = " registration-password-2026 ";
        String body = LoginTestClient.encryptedLoginBody(json, name, password);
        var registration = mvc.perform(post(PATH).header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(name))
            .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
            .andExpect(jsonPath("$.data.roles").doesNotExist()).andReturn();
        assertThat(registration.getResponse().getCookie("portfolio_session")).isNull();
        mvc.perform(get("/api/v1/auth/session"))
            .andExpect(jsonPath("$.data.loggedIn").value(false));
        UserAccountDO account = find(name);
        assertThat(account.getType()).isEqualTo(AccountType.NORMAL.code());
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ENABLED.code());
        assertThat(account.getPasswordHash()).startsWith("$argon2id$");
        assertThat(passwords.matches(password.trim(), account.getPasswordHash())).isTrue();
        assertThat(passwords.matches(password, account.getPasswordHash())).isFalse();

        String loginBody = LoginTestClient.encryptedLoginBody(json, name, password.trim());
        var login = mvc.perform(post("/api/v1/auth/session").header("Origin", ORIGIN)
                .contentType(MediaType.APPLICATION_JSON).content(loginBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.roles.length()").value(0)).andReturn();
        var cookie = login.getResponse().getCookie("portfolio_session");
        String csrf = json.readTree(login.getResponse().getContentAsString()).path("data").path("csrfToken").asText();
        try {
            mvc.perform(get("/api/v1/projects").param("view", "MANAGE").cookie(cookie))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(get("/api/v1/admin/profile").cookie(cookie)).andExpect(status().isForbidden());
        } finally {
            mvc.perform(delete("/api/v1/auth/session").cookie(cookie).header("X-CSRF-Token", csrf))
                .andExpect(status().isOk());
        }
    }

    @Test
    @Transactional
    void duplicateIsCaseInsensitiveAndClientCannotGrantOwnerOrDisableAccount() throws Exception {
        String name = uniqueName();
        String body = LoginTestClient.encryptedLoginBody(json, name, "test-password-2026");
        // 额外字段不能写入 DO；只有 Request 中的用户名与密文被绑定。
        String extraFields = body.substring(0, body.length() - 1) + ",\"type\":0,\"status\":0,\"roles\":[\"OWNER\"]}";
        mvc.perform(post(PATH).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(extraFields))
            .andExpect(status().isOk());
        assertThat(find(name).getType()).isEqualTo(AccountType.NORMAL.code());
        assertThat(find(name).getStatus()).isEqualTo(AccountStatus.ENABLED.code());
        String duplicate = LoginTestClient.encryptedLoginBody(json, name.toUpperCase(Locale.ROOT), "test-password-2026");
        mvc.perform(post(PATH).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(duplicate))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DATA_CONFLICT"));
        assertThat(accounts.selectCount(Wrappers.<UserAccountDO>lambdaQuery()
            .apply("LOWER(username) = {0}", name.toLowerCase(Locale.ROOT)))).isEqualTo(1);
    }

    @Test
    @Transactional
    void trimsBeforeValidatingMaximumUsernameLength() throws Exception {
        String name = uniqueName() + "x".repeat(28);
        assertThat(name).hasSize(64);
        String body = LoginTestClient.encryptedLoginBody(json, "  " + name + "  ", "test-password-2026");
        mvc.perform(post(PATH).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value(name));
        assertThat(find(name)).isNotNull();
    }

    @Test
    void rejectsPlaintextInvalidCiphertextAndMissingOrWrongOrigin() throws Exception {
        mvc.perform(post(PATH).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"visitor\",\"password\":\"plaintext-test\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIAL_PAYLOAD"));
        mvc.perform(post(PATH).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"visitor\",\"encryptedPassword\":\"not-base64\"}"))
            .andExpect(status().isBadRequest());
        String body = LoginTestClient.encryptedLoginBody(json, uniqueName(), "test-password-2026");
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_INVALID"));
        mvc.perform(post(PATH).header("Origin", "https://outside.test")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORIGIN_INVALID"));
    }

    @Test
    void businessValidationDoesNotInsertAccount() throws Exception {
        String name = uniqueName();
        for (String password : new String[] {"short", "😀".repeat(6), "internal password", "test\tpassword-2026",
                "中文密码测试-2026", "test-password！"}) {
            String body = LoginTestClient.encryptedLoginBody(json, name, password);
            mvc.perform(post(PATH).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        String body = LoginTestClient.encryptedLoginBody(json, "bad@name", "test-password-2026");
        mvc.perform(post(PATH).header("Origin", ORIGIN).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnprocessableEntity());
        assertThat(find(name)).isNull();
    }

    private String uniqueName() {
        return "reg_" + UUID.randomUUID().toString().replace("-", "");
    }

    private UserAccountDO find(String name) {
        return accounts.selectOne(Wrappers.<UserAccountDO>lambdaQuery().eq(UserAccountDO::getUsername, name));
    }
}
