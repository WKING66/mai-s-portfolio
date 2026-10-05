package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** 显式启用的双实例 HTTP/Redis/数据库验收，仅创建并清理本次唯一测试账号。 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_INTEGRATION_TEST", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class RegistrationRedisFlowTest extends AuthKeyTestSupport {
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void concurrentRegistrationUsesIndependentRedisWindowAcrossInstances() throws Exception {
        String prefix = "mai-portfolio:registration-test:" + UUID.randomUUID();
        String name = "reg_" + UUID.randomUUID().toString().replace("-", "");
        String password = "test-registration-password-2026";
        try (var first = start(prefix); var second = start(prefix)) {
            UserAccountMapper accounts = first.getBean(UserAccountMapper.class);
            String cookie = null;
            String csrf = null;
            try {
                String body = LoginTestClient.encryptedLoginBody(json, name, password);
                // 两个独立服务实例同时请求同一用户名；只有一个可以提交。
                var pendingA = http.sendAsync(request(first, "/api/v1/auth/register", "POST", body, null, null),
                    HttpResponse.BodyHandlers.ofString());
                var pendingB = http.sendAsync(request(second, "/api/v1/auth/register", "POST", body, null, null),
                    HttpResponse.BodyHandlers.ofString());
                var responseA = pendingA.join();
                var responseB = pendingB.join();
                assertThat(List.of(responseA.statusCode(), responseB.statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
                assertThat(responseA.headers().allValues("Set-Cookie")).isEmpty();
                assertThat(responseB.headers().allValues("Set-Cookie")).isEmpty();
                assertThat(accounts.selectCount(Wrappers.<UserAccountDO>lambdaQuery()
                    .eq(UserAccountDO::getUsername, name))).isEqualTo(1);
                assertThat(accounts.selectOne(Wrappers.<UserAccountDO>lambdaQuery()
                    .eq(UserAccountDO::getUsername, name)).getType()).isEqualTo(AccountType.NORMAL.code());

                String invalid = "{\"username\":\"visitor\",\"encryptedPassword\":\"invalid-base64\"}";
                assertThat(send(first, "/api/v1/auth/register", "POST", invalid, null, null).statusCode()).isEqualTo(400);
                // 注册窗口为 3 次，跨实例的第 4 次被拒绝；不能连带锁住登录。
                var limitedRegistration = send(second, "/api/v1/auth/register", "POST", body, null, null);
                assertThat(limitedRegistration.statusCode()).isEqualTo(429);
                assertThat(json.readTree(limitedRegistration.body()).path("code").asText()).isEqualTo("RATE_LIMITED");
                var login = send(second, "/api/v1/auth/session", "POST", body, null, null);
                assertThat(login.statusCode()).isEqualTo(200);
                assertThat(json.readTree(login.body()).path("data").path("roles").size()).isZero();
                cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                csrf = json.readTree(login.body()).path("data").path("csrfToken").asText();
                assertThat(json.readTree(send(first, "/api/v1/auth/session", "GET", null, cookie, null).body())
                    .path("data").path("loggedIn").asBoolean()).isTrue();
                assertThat(send(first, "/api/v1/admin/profile", "GET", null, cookie, null).statusCode()).isEqualTo(403);
                for (int attempt = 0; attempt < 4; attempt++) {
                    assertThat(send(attempt % 2 == 0 ? first : second, "/api/v1/auth/session", "POST", invalid, null, null)
                        .statusCode()).isEqualTo(400);
                }
                // 登录独立窗口为 5 次；一次成功加四次失败，跨实例的第 6 次才被拒绝。
                var limited = send(first, "/api/v1/auth/session", "POST", body, null, null);
                assertThat(limited.statusCode()).isEqualTo(429);
                assertThat(json.readTree(limited.body()).path("code").asText()).isEqualTo("RATE_LIMITED");
            } finally {
                if (cookie != null && csrf != null) {
                    assertThat(send(first, "/api/v1/auth/session", "DELETE", null, cookie, csrf).statusCode()).isEqualTo(200);
                }
                // name 为本方法生成的唯一值；不删除已有账号或其他命名空间。
                accounts.delete(Wrappers.<UserAccountDO>lambdaQuery().eq(UserAccountDO::getUsername, name));
                first.getBean(RedissonClient.class).getKeys().deleteByPattern(prefix + ":*");
            }
        }
    }

    private ServletWebServerApplicationContext start(String prefix) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(PortfolioApplication.class)
            .profiles("dev").run("--server.port=0", "--portfolio.test.in-memory-redis=false",
                "--spring.autoconfigure.exclude=", "--portfolio.bootstrap.enabled=false",
                "--spring.flyway.enabled=false", "--portfolio.media.storage=local",
                "--portfolio.security.rsa-private-key=" + privateKeyLocation(),
                "--portfolio.security.max-login-attempts-per-client=5",
                "--portfolio.security.max-registration-attempts-per-client=3",
                "--portfolio.redis.key-prefix=" + prefix, "--sa-token.cookie.secure=false",
                "--logging.file.name=" + System.getProperty("java.io.tmpdir") + "/mai-portfolio-tests/registration.log");
    }

    private HttpResponse<String> send(ServletWebServerApplicationContext context, String path,
            String method, String body, String cookie, String csrf) throws Exception {
        return http.send(request(context, path, method, body, cookie, csrf), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(ServletWebServerApplicationContext context, String path,
            String method, String body, String cookie, String csrf) {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
            + context.getWebServer().getPort() + path))
            .header("Origin", "http://127.0.0.1:3000").header("Content-Type", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        return builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
