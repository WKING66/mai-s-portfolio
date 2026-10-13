package dev.amai.portfolio.security.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.api.RedissonClient;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 使用真实 Redis 验证 Sa-Token 命名空间、TTL 和更新语义。 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_INTEGRATION_TEST", matches = "(?i)true")
@SpringBootTest(classes = NamespacedSaTokenDaoIntegrationTest.TestApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NamespacedSaTokenDaoIntegrationTest {
    private static final String KEY_PREFIX = "mai-portfolio:integration:" + UUID.randomUUID();

    @Resource
    private RedissonClient redisson;

    @Resource
    private SaTokenDao dao;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host",
            () -> environmentOrDefault("REDIS_HOST", "127.0.0.1"));
        registry.add("spring.data.redis.port",
            () -> environmentOrDefault("REDIS_PORT", "6379"));
        registry.add("spring.data.redis.database",
            () -> environmentOrDefault("REDIS_DATABASE", "0"));
        registry.add("spring.data.redis.password",
            () -> environmentOrDefault("REDIS_PASSWORD", ""));
        registry.add("portfolio.redis.key-prefix", () -> KEY_PREFIX);
    }

    @AfterAll
    void cleanUp() {
        redisson.getKeys().deleteByPattern(KEY_PREFIX + ":*");
    }

    @Test
    void storesUpdatesSearchesAndExpiresSaTokenData() {
        String tokenKey = "portfolio_session:token:integration-token";
        dao.set(tokenKey, "account-1", 30);

        long originalTimeout = dao.getTimeout(tokenKey);
        assertThat(dao.get(tokenKey)).isEqualTo("account-1");
        assertThat(originalTimeout).isBetween(1L, 30L);

        dao.update(tokenKey, "account-2");

        assertThat(dao.get(tokenKey)).isEqualTo("account-2");
        assertThat(dao.getTimeout(tokenKey)).isBetween(1L, originalTimeout);
        assertThat(dao.searchData("portfolio_session:token:", "integration", 0, 10, true))
            .contains(tokenKey);

        dao.updateTimeout(tokenKey, 2);
        assertThat(dao.getTimeout(tokenKey)).isBetween(1L, 2L);

        dao.delete(tokenKey);
        assertThat(dao.get(tokenKey)).isNull();
    }

    @Test
    void storesObjectsAndSessionsThroughSaTokenAdapters() {
        String objectKey = "portfolio_session:role-list:account-1";
        List<String> roles = new ArrayList<>(List.of("owner"));
        dao.setObject(objectKey, roles, 30);

        assertThat(dao.getObject(objectKey)).isEqualTo(roles);

        String sessionId = "portfolio_session:session:integration-session";
        SaSession session = new SaSession(sessionId).set("accountId", "account-1");
        dao.setSession(session, 30);

        SaSession storedSession = dao.getSession(sessionId);
        assertThat(storedSession.getId()).isEqualTo(sessionId);
        assertThat(storedSession.get("accountId")).isEqualTo("account-1");
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }
}
