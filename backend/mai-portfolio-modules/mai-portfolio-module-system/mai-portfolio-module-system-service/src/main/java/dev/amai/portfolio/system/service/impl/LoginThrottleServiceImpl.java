package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import org.springframework.stereotype.Service;

/**
 * 登录场景固定窗口限流服务。
 *
 * <p>计数由 Redis Lua 脚本原子维护，所有应用实例共享同一窗口。</p>
 */
@Service
public class LoginThrottleServiceImpl implements LoginThrottleService {
    private final SecurityProperties security;
    private final FixedWindowRateLimiter rateLimiter;

    public LoginThrottleServiceImpl(SecurityProperties security,
            FixedWindowRateLimiter rateLimiter) {
        this.security = security;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void acquireChallengePermit(String clientKey) {
        acquire(RedisKeys.CHALLENGE_RATE_NAMESPACE, clientKey, security.maxChallengesPerClient(),
            security.challengeRateWindow());
    }

    @Override
    public void acquireLoginPermit(String clientKey) {
        acquire(RedisKeys.LOGIN_RATE_NAMESPACE, clientKey, security.maxLoginAttemptsPerClient(),
            security.loginAttemptWindow());
    }

    @Override
    public void recordLoginSuccess(String clientKey) {
        rateLimiter.reset(RedisKeys.LOGIN_RATE_NAMESPACE, clientKey);
    }

    private void acquire(String namespace, String clientKey, int limit,
            java.time.Duration duration) {
        if (!rateLimiter.tryAcquire(namespace, clientKey, limit, duration)) {
            throw rateLimited();
        }
    }

    private ApiException rateLimited() {
        return new ApiException(ApiErrorCode.RATE_LIMITED, SystemMessageConstants.LOGIN_RATE_LIMITED);
    }
}
