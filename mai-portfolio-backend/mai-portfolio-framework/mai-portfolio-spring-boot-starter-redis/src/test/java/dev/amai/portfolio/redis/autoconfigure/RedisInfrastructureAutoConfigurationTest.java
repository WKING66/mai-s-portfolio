package dev.amai.portfolio.redis.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.redis.rate.RedisRateLimiter;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RedisInfrastructureAutoConfigurationTest {
    private final ApplicationContextRunner applicationContextRunner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(RedisInfrastructureAutoConfiguration.class))
                    .withPropertyValues("portfolio.redis.key-prefix=portfolio-test");

    @Test
    void backsOffWhenNoRedissonClientExists() {
        applicationContextRunner.run(
                context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RedisKeys.class);
                    assertThat(context).doesNotHaveBean(RedisRateLimiter.class);
                });
    }

    @Test
    void bindsPrefixAndPreservesDefaultBeanNames() {
        applicationContextRunner
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(RedisKeys.class);
                            assertThat(context).hasSingleBean(RedisRateLimiter.class);
                            assertThat(
                                            context.getBean("redisKeys", RedisKeys.class)
                                                    .saTokenPrefix())
                                    .isEqualTo("portfolio-test:sa-token:");
                            assertThat(context.getBean("redisRateLimiter"))
                                    .isInstanceOf(RedisRateLimiter.class);
                        });
    }

    @Test
    void preservesCallerProvidedKeysAndRateLimiter() {
        RedisKeys customRedisKeys = new RedisKeys(new RedisInfrastructureProperties("custom"));
        RedisRateLimiter customRedisRateLimiter = mock(RedisRateLimiter.class);
        applicationContextRunner
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean("customRedisKeys", RedisKeys.class, () -> customRedisKeys)
                .withBean(
                        "customRedisRateLimiter",
                        RedisRateLimiter.class,
                        () -> customRedisRateLimiter)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(RedisKeys.class);
                            assertThat(context).hasSingleBean(RedisRateLimiter.class);
                            assertThat(context.getBean(RedisKeys.class)).isSameAs(customRedisKeys);
                            assertThat(context.getBean(RedisRateLimiter.class))
                                    .isSameAs(customRedisRateLimiter);
                            assertThat(context).doesNotHaveBean("redisKeys");
                            assertThat(context).doesNotHaveBean("redisRateLimiter");
                        });
    }

    @Test
    void rejectsBlankConfiguredPrefix() {
        applicationContextRunner
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withPropertyValues("portfolio.redis.key-prefix= ")
                .run(context -> assertThat(context).hasFailed());
    }
}
