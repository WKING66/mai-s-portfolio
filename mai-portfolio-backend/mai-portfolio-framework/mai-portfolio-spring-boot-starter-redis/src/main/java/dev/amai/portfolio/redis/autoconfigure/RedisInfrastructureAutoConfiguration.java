package dev.amai.portfolio.redis.autoconfigure;

import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.redis.rate.RedissonFixedWindowRateLimiter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 在已有 RedissonClient 后注册键名和限流能力，允许调用方提供同类型 Bean 替代默认实现。 */
@AutoConfiguration(afterName = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
@ConditionalOnBean(RedissonClient.class)
@EnableConfigurationProperties(RedisInfrastructureProperties.class)
public class RedisInfrastructureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    RedisKeys redisKeys(RedisInfrastructureProperties redisInfrastructureProperties) {
        return new RedisKeys(redisInfrastructureProperties);
    }

    @Bean
    @ConditionalOnMissingBean(FixedWindowRateLimiter.class)
    FixedWindowRateLimiter fixedWindowRateLimiter(
            RedissonClient redissonClient, RedisKeys redisKeys) {
        return new RedissonFixedWindowRateLimiter(redissonClient, redisKeys);
    }
}
