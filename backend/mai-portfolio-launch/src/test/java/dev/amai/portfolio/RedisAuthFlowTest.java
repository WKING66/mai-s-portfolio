package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.security.session.NamespacedSaTokenDao;
import dev.amai.portfolio.system.service.PasswordCryptoService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** 两个真实 HTTP 实例共享固定密钥与隔离的 Redis 命名空间；不修改用户表。 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_INTEGRATION_TEST", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class RedisAuthFlowTest extends AuthKeyTestSupport {
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void fixedKeyLoginAndRedisSessionWorkAcrossTwoInstances() throws Exception {
        String prefix = "mai-portfolio:auth-test:" + UUID.randomUUID();
        try (var first = start(prefix); var second = start(prefix)) {
            try {
                assertThat(first.getBean(PasswordCryptoService.class))
                    .isNotSameAs(second.getBean(PasswordCryptoService.class));
                assertThat(first.getBean(SaTokenDao.class))
                    .isInstanceOf(NamespacedSaTokenDao.class);
                String body = LoginTestClient.encryptedLoginBody(json, "owner", System.getenv("OWNER_PASSWORD"));
                var loginA = request(first, "POST", body, null, null);
                assertThat(loginA.statusCode()).isEqualTo(200);
                String setCookie = loginA.headers().firstValue("Set-Cookie").orElseThrow();
                assertThat(setCookie).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/");
                String cookie = setCookie.split(";", 2)[0];
                assertThat(first.getBean(RedissonClient.class).getKeys()
                    .countExists(prefix + ":sa-token:"
                        + StpUtil.getStpLogic().splicingKeyTokenValue(
                            cookie.substring(cookie.indexOf('=') + 1)))).isEqualTo(1);
                var sessionB = request(second, "GET", null, cookie, null);
                assertThat(sessionB.statusCode()).isEqualTo(200);
                assertThat(json.readTree(sessionB.body()).path("data").path("loggedIn").asBoolean()).isTrue();
                assertThat(request(second, "DELETE", null, cookie, null).statusCode()).isEqualTo(403);
                assertThat(request(second, "DELETE", null, cookie, "wrong-csrf").statusCode()).isEqualTo(403);

                // 相同密文可以直接由另一个实例处理，没有挑战签发/消费状态或额外取公钥请求。
                var loginB = request(second, "POST", body, null, null);
                assertThat(loginB.statusCode()).isEqualTo(200);
                String csrf = json.readTree(loginB.body()).path("data").path("csrfToken").asText();
                cookie = loginB.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                assertThat(request(first, "DELETE", null, cookie, csrf).statusCode()).isEqualTo(200);
                assertThat(json.readTree(request(second, "GET", null, cookie, null).body())
                    .path("data").path("loggedIn").asBoolean()).isFalse();
                String wrongPassword = LoginTestClient.encryptedLoginBody(json, "owner", "wrong-password");
                // 两次成功登录同样占用窗口；成功认证不得清除之前的尝试计数。
                for (int attempt = 0; attempt < 3; attempt++) {
                    assertThat(request(first, "POST", wrongPassword, null, null).statusCode()).isEqualTo(401);
                }
                assertThat(request(second, "POST", wrongPassword, null, null).statusCode()).isEqualTo(429);
            } finally {
                // 失败也只清理本次测试命名空间，不能影响真实会话或其他应用。
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
                "--portfolio.redis.key-prefix=" + prefix, "--sa-token.cookie.secure=true",
                "--logging.file.name=" + System.getProperty("java.io.tmpdir") + "/mai-portfolio-tests/auth.log");
    }

    private HttpResponse<String> request(ServletWebServerApplicationContext context, String method,
            String body, String cookie, String csrf) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
            + context.getWebServer().getPort() + "/api/v1/auth/session"))
            .header("Origin", "http://127.0.0.1:3000").header("Content-Type", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header("X-CSRF-Token", csrf);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
