package dev.amai.portfolio.logging.autoconfigure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 内置敏感字段始终生效；配置仅可追加要隐藏的业务字段。 */
@ConfigurationProperties(prefix = "portfolio.api-log")
@Validated
public record ApiLogProperties(
        boolean enabled,

        @Min(ApiLogProperties.MIN_PAYLOAD_LENGTH)
        @Max(ApiLogProperties.MAX_PAYLOAD_LENGTH)
        int maxPayloadLength,

        @NotNull
        List<String> additionalSensitiveFields

    ) {
    private static final int MIN_PAYLOAD_LENGTH = 256;

    private static final int MAX_PAYLOAD_LENGTH = 16_384;
}
