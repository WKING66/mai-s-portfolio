package dev.amai.portfolio.system.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

/** 固定私钥来自外部 Secret 文件，非开发环境默认要求 HTTPS。 */
@Validated
@ConfigurationProperties(prefix = "portfolio.security")
public record SecurityProperties(@NotEmpty List<String> allowedOrigins,
                                 @NotNull Resource rsaPrivateKey,
                                 boolean requireHttps,
                                 @Positive int maxLoginAttemptsPerClient,
                                 @NotNull Duration loginAttemptWindow,
                                 @Positive @DefaultValue("5") int maxRegistrationAttemptsPerClient,
                                 @NotNull @DefaultValue("5m") Duration registrationAttemptWindow) {
}
