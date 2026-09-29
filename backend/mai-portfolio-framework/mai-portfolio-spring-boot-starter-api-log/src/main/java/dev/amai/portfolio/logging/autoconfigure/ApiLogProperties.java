package dev.amai.portfolio.logging.autoconfigure;

import java.util.List;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 内置敏感字段始终生效；配置仅可追加要隐藏的业务字段。 */
@ConfigurationProperties(prefix = "portfolio.api-log")
@Validated
public record ApiLogProperties(boolean enabled,
                               @Min(256) @Max(16384) int maxPayloadLength,
                               @NotNull List<String> additionalSensitiveFields) {
}
