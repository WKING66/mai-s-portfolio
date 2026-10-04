package dev.amai.portfolio.redis.autoconfigure;

import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.redis.rate.RedissonFixedWindowRateLimiter;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** 注册项目统一使用的 Redisson 基础设施能力。 */
@AutoConfiguration(afterName = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
@ConditionalOnBean(RedissonClient.class)
@EnableConfigurationProperties(RedisInfrastructureProperties.class)
public class RedisInfrastructureAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    RedisKeys redisKeys(RedisInfrastructureProperties properties) {
        return new RedisKeys(properties);
    }

    @Bean
    @ConditionalOnMissingBean(FixedWindowRateLimiter.class)
    FixedWindowRateLimiter fixedWindowRateLimiter(RedissonClient redisson, RedisKeys keys) {
        return new RedissonFixedWindowRateLimiter(redisson, keys);
    }
}
