package dev.amai.portfolio.redis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.common.lock.DistributedLockService;
import dev.amai.portfolio.redis.ExpiringStringMap;
import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.redis.lock.RedissonDistributedLockService;
import dev.amai.portfolio.redis.rate.RedissonFixedWindowRateLimiter;
import dev.amai.portfolio.redis.store.RedissonExpiringStringMap;
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
    private ExpiringStringMap expiringMap;
    private DistributedLockService locks;
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
        expiringMap = new RedissonExpiringStringMap(redisson, keys);
        locks = new RedissonDistributedLockService(redisson, keys);
    }

    @AfterEach
    void cleanUp() {
        if (redisson != null) {
            redisson.getKeys().deleteByPattern(keyPrefix + ":*");
            redisson.shutdown();
        }
    }

    @Test
    void supportsAtomicRateLimitOneTimeDataAndDistributedLock() {
        assertThat(rateLimiter.tryAcquire("login", "client-a", 2, Duration.ofSeconds(10)))
            .isTrue();
        assertThat(rateLimiter.tryAcquire("login", "client-a", 2, Duration.ofSeconds(10)))
            .isTrue();
        assertThat(rateLimiter.tryAcquire("login", "client-a", 2, Duration.ofSeconds(10)))
            .isFalse();

        expiringMap.put("challenge", "challenge-1", "payload", Duration.ofSeconds(10));
        assertThat(expiringMap.take("challenge", "challenge-1")).isEqualTo("payload");
        assertThat(expiringMap.take("challenge", "challenge-1")).isNull();

        assertThat(locks.execute("integration-lock", Duration.ofSeconds(1),
            Duration.ofSeconds(5), () -> "locked")).isEqualTo("locked");
    }

    @Test
    void excludesExpiredEntriesFromSizeAndConsumption() throws InterruptedException {
        expiringMap.put("challenge", "expired-challenge", "payload", Duration.ofMillis(100));
        assertThat(expiringMap.size("challenge")).isEqualTo(1);

        Thread.sleep(150);

        assertThat(expiringMap.size("challenge")).isZero();
        assertThat(expiringMap.take("challenge", "expired-challenge")).isNull();
    }

    private String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
