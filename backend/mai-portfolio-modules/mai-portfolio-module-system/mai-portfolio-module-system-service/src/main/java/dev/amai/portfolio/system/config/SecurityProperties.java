package dev.amai.portfolio.system.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "portfolio.security")
public record SecurityProperties(@NotEmpty List<String> allowedOrigins,
                                 @NotNull Duration loginChallengeTtl,
                                 @Positive int maxOutstandingChallenges,
                                 @Positive int maxChallengesPerClient,
                                 @NotNull Duration challengeRateWindow,
                                 @Positive int maxLoginAttemptsPerClient,
                                 @NotNull Duration loginAttemptWindow,
                                 @Positive int maxTrackedLoginClients) {
}
