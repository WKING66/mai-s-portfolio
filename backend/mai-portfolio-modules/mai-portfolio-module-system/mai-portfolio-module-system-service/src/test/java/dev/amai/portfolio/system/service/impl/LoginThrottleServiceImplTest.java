package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginThrottleServiceImplTest {
    @Test
    void limitsEachClientIndependentlyAndClearsSuccessfulLoginWindow() {
        SecurityProperties properties = new SecurityProperties(List.of("http://localhost"),
            Duration.ofMinutes(1), 32, 2, Duration.ofMinutes(1),
            2, Duration.ofMinutes(10), 64);
        LoginThrottleServiceImpl throttle = new LoginThrottleServiceImpl(properties);

        throttle.acquireLoginPermit("client-a");
        throttle.acquireLoginPermit("client-a");
        assertThatThrownBy(() -> throttle.acquireLoginPermit("client-a"))
            .isInstanceOf(ApiException.class);
        assertThatCode(() -> throttle.acquireLoginPermit("client-b")).doesNotThrowAnyException();

        throttle.recordLoginSuccess("client-a");
        assertThatCode(() -> throttle.acquireLoginPermit("client-a")).doesNotThrowAnyException();
    }

    @Test
    void limitsChallengeGenerationBeforeCreatingAnotherKeyPair() {
        SecurityProperties properties = new SecurityProperties(List.of("http://localhost"),
            Duration.ofMinutes(1), 32, 1, Duration.ofMinutes(1),
            3, Duration.ofMinutes(10), 64);
        LoginThrottleServiceImpl throttle = new LoginThrottleServiceImpl(properties);

        throttle.acquireChallengePermit("client-a");

        assertThatThrownBy(() -> throttle.acquireChallengePermit("client-a"))
            .isInstanceOf(ApiException.class);
    }
}
