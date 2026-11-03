package dev.amai.portfolio.redis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.redis.rate.RedisRateLimiter;
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
    private RedissonClient redissonClient;
    private RedisRateLimiter rateLimiter;
    private String keyPrefix;

    @BeforeEach
    void setUp() {
        String host = environmentOrDefault("REDIS_HOST", "127.0.0.1");
        String port = environmentOrDefault("REDIS_PORT", "6379");
        Config redissonConfig = new Config();
        redissonConfig.setCodec(StringCodec.INSTANCE);
        SingleServerConfig singleServerConfig =
                redissonConfig
                        .useSingleServer()
                        .setAddress("redis://" + host + ':' + port)
                        .setDatabase(Integer.parseInt(environmentOrDefault("REDIS_DATABASE", "0")));
        String password = System.getenv("REDIS_PASSWORD");
        if (password != null && !password.isBlank()) {
            singleServerConfig.setPassword(password);
        }
        redissonClient = Redisson.create(redissonConfig);
        keyPrefix = "mai-portfolio:integration:" + UUID.randomUUID();
        RedisKeys redisKeys = new RedisKeys(new RedisInfrastructureProperties(keyPrefix));
        rateLimiter = new RedisRateLimiter(redissonClient, redisKeys);
    }

    @AfterEach
    void cleanUp() {
        if (redissonClient != null) {
            redissonClient.getKeys().deleteByPattern(keyPrefix + ":*");
            redissonClient.shutdown();
        }
    }

    @Test
    void limitsClientsAndEndpointsIndependently() {
        assertThat(rateLimiter.fixedWindow("login", "client-a", 2, 10)).isTrue();
        assertThat(rateLimiter.fixedWindow("login", "client-a", 2, 10)).isTrue();
        assertThat(rateLimiter.fixedWindow("login", "client-a", 2, 10))
                .isFalse();

        assertThat(rateLimiter.fixedWindow("login", "client-b", 2, 10)).isTrue();
        assertThat(rateLimiter.fixedWindow("register", "client-a", 2, 10)).isTrue();
    }

    @Test
    void permitsAfterWindowExpires() throws InterruptedException {
        assertThat(rateLimiter.fixedWindow("login", "expiry-client", 1, 1))
                .isTrue();
        assertThat(rateLimiter.fixedWindow("login", "expiry-client", 1, 1))
                .isFalse();
        // 等待窗口过期并预留调度余量，避免恰好落在 TTL 边界。
        Thread.sleep(Duration.ofSeconds(1).plusMillis(250).toMillis());
        assertThat(rateLimiter.fixedWindow("login", "expiry-client", 1, 1))
                .isTrue();
    }

    @Test
    void bucketAllowsBurstAndRefillsWithoutSharingOtherClients() throws InterruptedException {
        assertThat(rateLimiter.tokenBucket("chat", "a", 2, 2)).isTrue();
        assertThat(rateLimiter.tokenBucket("chat", "a", 2, 2)).isTrue();
        assertThat(rateLimiter.tokenBucket("chat", "a", 2, 2)).isFalse();
        assertThat(rateLimiter.tokenBucket("chat", "b", 2, 2)).isTrue();
        Thread.sleep(650);
        assertThat(rateLimiter.tokenBucket("chat", "a", 2, 2)).isTrue();
    }

    @Test
    void concurrentRequestsCannotExceedEitherInitialBudget() {
        for (boolean bucket : new boolean[] {false, true}) {
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
                for (int i = 0; i < 24; i++) {
                    tasks.add(executor.submit(() -> bucket
                            ? rateLimiter.tokenBucket("concurrent", "a", 5, 0.01)
                            : rateLimiter.fixedWindow("concurrent", "a", 5, 30)));
                }
                int accepted = 0;
                for (var task : tasks) {
                    try {
                        if (task.get()) {
                            accepted++;
                        }
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                }
                assertThat(accepted).isEqualTo(5);
            }
        }
    }

    private String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
