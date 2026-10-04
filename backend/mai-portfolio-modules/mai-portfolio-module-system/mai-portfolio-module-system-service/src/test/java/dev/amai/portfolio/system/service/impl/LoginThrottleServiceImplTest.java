package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private SecurityProperties properties() {
        return new SecurityProperties(List.of("http://localhost"),
            new org.springframework.core.io.ByteArrayResource(new byte[0]), false,
            2, Duration.ofMinutes(10));
    }
}
