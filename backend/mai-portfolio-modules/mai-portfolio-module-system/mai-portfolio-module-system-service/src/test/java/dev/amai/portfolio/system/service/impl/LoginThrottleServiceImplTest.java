package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginThrottleServiceImplTest {
    @Test
    void countsAllAttemptsUntilWindowExpiryWithoutCrossAccountReset() {
        SecurityProperties properties = properties();
        FixedWindowRateLimiter rateLimiter = mock(FixedWindowRateLimiter.class);
        when(rateLimiter.tryAcquire("auth:login", "client-a", 2, Duration.ofMinutes(10)))
            .thenReturn(true, true, false, true);
        when(rateLimiter.tryAcquire("auth:login", "client-b", 2, Duration.ofMinutes(10)))
            .thenReturn(true);
        LoginThrottleServiceImpl throttle = new LoginThrottleServiceImpl(properties, rateLimiter);

        throttle.acquireLoginPermit("client-a");
        throttle.acquireLoginPermit("client-a");
        assertThatThrownBy(() -> throttle.acquireLoginPermit("client-a"))
            .isInstanceOf(ApiException.class);
        assertThatCode(() -> throttle.acquireLoginPermit("client-b")).doesNotThrowAnyException();

        // 窗口恢复只能来自 Redis TTL 到期；业务层没有成功认证后清零的入口。
        verify(rateLimiter, never()).reset("auth:login", "client-a");
        assertThatCode(() -> throttle.acquireLoginPermit("client-a")).doesNotThrowAnyException();
    }

    @Test
    void registrationUsesIndependentNamespaceLimitAndWindow() {
        FixedWindowRateLimiter rateLimiter = mock(FixedWindowRateLimiter.class);
        when(rateLimiter.tryAcquire("auth:register", "client-a", 3, Duration.ofMinutes(5)))
            .thenReturn(true);
        when(rateLimiter.tryAcquire("auth:login", "client-a", 2, Duration.ofMinutes(10)))
            .thenReturn(true);
        LoginThrottleServiceImpl throttle = new LoginThrottleServiceImpl(properties(), rateLimiter);

        assertThatCode(() -> throttle.acquireRegistrationPermit("client-a")).doesNotThrowAnyException();
        assertThatCode(() -> throttle.acquireLoginPermit("client-a")).doesNotThrowAnyException();

        verify(rateLimiter).tryAcquire("auth:register", "client-a", 3, Duration.ofMinutes(5));
        verify(rateLimiter).tryAcquire("auth:login", "client-a", 2, Duration.ofMinutes(10));
    }

    @Test
    void registrationExhaustionDoesNotBlockLoginOrOtherClients() {
        FixedWindowRateLimiter rateLimiter = mock(FixedWindowRateLimiter.class);
        when(rateLimiter.tryAcquire("auth:register", "client-a", 3, Duration.ofMinutes(5)))
            .thenReturn(true, true, true, false);
        when(rateLimiter.tryAcquire("auth:register", "client-b", 3, Duration.ofMinutes(5)))
            .thenReturn(true);
        when(rateLimiter.tryAcquire("auth:login", "client-a", 2, Duration.ofMinutes(10)))
            .thenReturn(true);
        LoginThrottleServiceImpl throttle = new LoginThrottleServiceImpl(properties(), rateLimiter);

        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.acquireRegistrationPermit("client-a");
        }
        assertThatThrownBy(() -> throttle.acquireRegistrationPermit("client-a"))
            .isInstanceOf(ApiException.class);
        assertThatCode(() -> throttle.acquireLoginPermit("client-a")).doesNotThrowAnyException();
        assertThatCode(() -> throttle.acquireRegistrationPermit("client-b")).doesNotThrowAnyException();

        verify(rateLimiter, times(4)).tryAcquire("auth:register", "client-a", 3, Duration.ofMinutes(5));
        verify(rateLimiter, never()).reset("auth:register", "client-a");
    }

    private SecurityProperties properties() {
        return new SecurityProperties(List.of("http://localhost"),
            new org.springframework.core.io.ByteArrayResource(new byte[0]), false,
            2, Duration.ofMinutes(10), 3, Duration.ofMinutes(5));
    }
}
