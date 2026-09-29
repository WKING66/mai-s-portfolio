package dev.amai.portfolio.redis.autoconfigure;

import dev.amai.portfolio.common.lock.DistributedLockService;
import dev.amai.portfolio.redis.ExpiringStringMap;
import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.redis.lock.RedissonDistributedLockService;
import dev.amai.portfolio.redis.rate.RedissonFixedWindowRateLimiter;
import dev.amai.portfolio.redis.store.RedissonExpiringStringMap;
import dev.amai.portfolio.redis.support.RedisKeyFactory;
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
    RedisKeyFactory redisKeyFactory(RedisInfrastructureProperties properties) {
        return new RedisKeyFactory(properties);
    }

    @Bean
    @ConditionalOnMissingBean(DistributedLockService.class)
    DistributedLockService distributedLockService(RedissonClient redisson, RedisKeyFactory keys) {
        return new RedissonDistributedLockService(redisson, keys);
    }

    @Bean
    @ConditionalOnMissingBean(FixedWindowRateLimiter.class)
    FixedWindowRateLimiter fixedWindowRateLimiter(RedissonClient redisson, RedisKeyFactory keys) {
        return new RedissonFixedWindowRateLimiter(redisson, keys);
    }

    @Bean
    @ConditionalOnMissingBean(ExpiringStringMap.class)
    ExpiringStringMap expiringStringMap(RedissonClient redisson, RedisKeyFactory keys) {
        return new RedissonExpiringStringMap(redisson, keys);
    }
}
