package dev.amai.portfolio.redis.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.redis.rate.RedissonFixedWindowRateLimiter;
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
                    assertThat(context).doesNotHaveBean(FixedWindowRateLimiter.class);
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
                            assertThat(context).hasSingleBean(FixedWindowRateLimiter.class);
                            assertThat(
                                            context.getBean("redisKeys", RedisKeys.class)
                                                    .saTokenPrefix())
                                    .isEqualTo("portfolio-test:sa-token:");
                            assertThat(context.getBean("fixedWindowRateLimiter"))
                                    .isInstanceOf(RedissonFixedWindowRateLimiter.class);
                        });
    }

    @Test
    void preservesCallerProvidedKeysAndRateLimiter() {
        RedisKeys customRedisKeys = new RedisKeys(new RedisInfrastructureProperties("custom"));
        FixedWindowRateLimiter customFixedWindowRateLimiter = mock(FixedWindowRateLimiter.class);
        applicationContextRunner
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean("customRedisKeys", RedisKeys.class, () -> customRedisKeys)
                .withBean(
                        "customFixedWindowRateLimiter",
                        FixedWindowRateLimiter.class,
                        () -> customFixedWindowRateLimiter)
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context).hasSingleBean(RedisKeys.class);
                            assertThat(context).hasSingleBean(FixedWindowRateLimiter.class);
                            assertThat(context.getBean(RedisKeys.class)).isSameAs(customRedisKeys);
                            assertThat(context.getBean(FixedWindowRateLimiter.class))
                                    .isSameAs(customFixedWindowRateLimiter);
                            assertThat(context).doesNotHaveBean("redisKeys");
                            assertThat(context).doesNotHaveBean("fixedWindowRateLimiter");
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
