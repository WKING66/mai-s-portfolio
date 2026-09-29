package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 单实例固定窗口限流实现。
 *
 * <p>它负责第一版单节点服务的入口保护；未来扩容为多实例时保持接口不变，替换为 Redis 实现。</p>
 */
@Service
public class LoginThrottleServiceImpl implements LoginThrottleService {
    private final Map<String, Window> challengeWindows = new HashMap<>();
    private final Map<String, Window> loginWindows = new HashMap<>();
    private final SecurityProperties security;

    public LoginThrottleServiceImpl(SecurityProperties security) {
        this.security = security;
    }

    @Override
    public synchronized void acquireChallengePermit(String clientKey) {
        acquire(challengeWindows, clientKey, security.maxChallengesPerClient(),
            security.challengeRateWindow());
    }

    @Override
    public synchronized void acquireLoginPermit(String clientKey) {
        acquire(loginWindows, clientKey, security.maxLoginAttemptsPerClient(),
            security.loginAttemptWindow());
    }

    @Override
    public synchronized void recordLoginSuccess(String clientKey) {
        loginWindows.remove(clientKey);
    }

    private void acquire(Map<String, Window> windows, String clientKey, int limit, Duration duration) {
        Instant now = Instant.now();
        windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        Window current = windows.get(clientKey);
        if (current == null) {
            if (trackedClientCount() >= security.maxTrackedLoginClients()) {
                throw rateLimited();
            }
            windows.put(clientKey, new Window(1, now.plus(duration)));
            return;
        }
        if (current.count() >= limit) {
            throw rateLimited();
        }
        windows.put(clientKey, new Window(current.count() + 1, current.expiresAt()));
    }

    private int trackedClientCount() {
        return challengeWindows.size() + loginWindows.size();
    }

    private ApiException rateLimited() {
        return new ApiException(ApiErrorCode.RATE_LIMITED, SystemMessageConstants.LOGIN_RATE_LIMITED);
    }

    private record Window(int count, Instant expiresAt) {
    }
}
