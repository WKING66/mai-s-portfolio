package dev.amai.portfolio.redis.autoconfigure;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 项目自有 Redis 键空间配置。 */
@Validated
@ConfigurationProperties(prefix = "portfolio.redis")
public record RedisInfrastructureProperties(@NotBlank String keyPrefix) {
}
