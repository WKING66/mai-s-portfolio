package dev.amai.portfolio.config;

import java.util.List;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "portfolio.security")
public record SecurityProperties(List<String> allowedOrigins, Duration loginChallengeTtl,
                                 int maxOutstandingChallenges) {
}
