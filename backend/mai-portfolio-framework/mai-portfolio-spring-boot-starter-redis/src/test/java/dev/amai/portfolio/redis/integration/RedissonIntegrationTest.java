package dev.amai.portfolio.redis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.redis.rate.RedissonFixedWindowRateLimiter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;

/** 真实 Redis 验收测试，仅操作随机命名空间并在结束后清理。 */
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_INTEGRATION_TEST", matches = "(?i)true")
class RedissonIntegrationTest {
    private RedissonClient redisson;
    private FixedWindowRateLimiter rateLimiter;
    private String keyPrefix;

    @BeforeEach
    void setUp() {
        String host = environmentOrDefault("REDIS_HOST", "127.0.0.1");
        String port = environmentOrDefault("REDIS_PORT", "6379");
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        SingleServerConfig server = config.useSingleServer()
            .setAddress("redis://" + host + ':' + port)
            .setDatabase(Integer.parseInt(environmentOrDefault("REDIS_DATABASE", "0")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            server.setPassword(password);
        }
        redisson = Redisson.create(config);
        keyPrefix = "mai-portfolio:integration:" + UUID.randomUUID();
        RedisKeys keys = new RedisKeys(new RedisInfrastructureProperties(keyPrefix));
        rateLimiter = new RedissonFixedWindowRateLimiter(redisson, keys);
    }

    @AfterEach
    void cleanUp() {
        if (redisson != null) {
            redisson.getKeys().deleteByPattern(keyPrefix + ":*");
            redisson.shutdown();
        }
    }

    @Test
    void supportsAtomicRateLimitAndReset() {
        assertThat(rateLimiter.tryAcquire("login", "client-a", 2, Duration.ofSeconds(10)))
            .isTrue();
        assertThat(rateLimiter.tryAcquire("login", "client-a", 2, Duration.ofSeconds(10)))
            .isTrue();
        assertThat(rateLimiter.tryAcquire("login", "client-a", 2, Duration.ofSeconds(10)))
            .isFalse();

        assertThat(rateLimiter.tryAcquire("login", "client-b", 2, Duration.ofSeconds(10))).isTrue();
        rateLimiter.reset("login", "client-a");
        assertThat(rateLimiter.tryAcquire("login", "client-a", 2, Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void permitsAfterWindowExpires() throws InterruptedException {
        assertThat(rateLimiter.tryAcquire("login", "expiry-client", 1, Duration.ofSeconds(1))).isTrue();
        assertThat(rateLimiter.tryAcquire("login", "expiry-client", 1, Duration.ofSeconds(1))).isFalse();
        Thread.sleep(1250);
        assertThat(rateLimiter.tryAcquire("login", "expiry-client", 1, Duration.ofSeconds(1))).isTrue();
    }

    private String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
