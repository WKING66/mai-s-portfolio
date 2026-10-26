package dev.amai.portfolio.redis.autoconfigure;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 项目自有 Redis 键空间配置；前缀隔离本应用的限流和会话键，不由业务调用方拼接。 */
@Validated
@ConfigurationProperties(prefix = "portfolio.redis")
public record RedisInfrastructureProperties(
        @NotBlank
        String keyPrefix) {}
