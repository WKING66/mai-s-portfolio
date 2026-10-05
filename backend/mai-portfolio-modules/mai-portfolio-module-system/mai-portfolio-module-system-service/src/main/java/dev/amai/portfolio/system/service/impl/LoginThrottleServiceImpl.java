package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 登录与注册场景的固定窗口限流服务，分别使用命名空间和安全配置。
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
    public void acquireLoginPermit(String clientKey) {
        acquire(RedisKeys.LOGIN_RATE_NAMESPACE, clientKey, security.maxLoginAttemptsPerClient(),
            security.loginAttemptWindow(), SystemMessageConstants.LOGIN_RATE_LIMITED);
    }

    @Override
    public void acquireRegistrationPermit(String clientKey) {
        acquire(RedisKeys.REGISTRATION_RATE_NAMESPACE, clientKey,
            security.maxRegistrationAttemptsPerClient(), security.registrationAttemptWindow(),
            SystemMessageConstants.REGISTER_RATE_LIMITED);
    }

    private void acquire(String namespace, String clientKey, int limit,
            Duration duration, String message) {
        if (!rateLimiter.tryAcquire(namespace, clientKey, limit, duration)) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED, message);
        }
    }
}
