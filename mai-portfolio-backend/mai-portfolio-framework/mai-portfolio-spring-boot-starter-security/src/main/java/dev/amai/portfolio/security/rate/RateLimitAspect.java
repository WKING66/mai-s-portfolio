package dev.amai.portfolio.security.rate;

import dev.amai.portfolio.redis.rate.RedisRateLimiter;
import dev.amai.portfolio.security.SecurityMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 只做入口频率限制，不解密凭据、查询账号或重复鉴权；超限时不进入 Controller。 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RateLimitAspect {
    private final RedisRateLimiter redisRateLimiter;

    public RateLimitAspect(RedisRateLimiter redisRateLimiter) {
        this.redisRateLimiter = redisRateLimiter;
    }

    @Around("@annotation(rateLimit)")
    public Object limit(ProceedingJoinPoint invocation, RateLimit rateLimit) throws Throwable {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new IllegalStateException("@RateLimit 只能用于 HTTP 请求入口");
        }
        var method = AopUtils.getMostSpecificMethod(((MethodSignature) invocation.getSignature()).getMethod(),
                invocation.getTarget().getClass());
        // 登录和注册使用不同方法身份，成功请求也计数，不因账号登录成功而清空同 IP 的尝试额度。
        // 沿用容器给出的来源地址；不读取客户端可伪造的 X-Forwarded-For，不在这里额外鉴权。
        String client = attributes.getRequest().getRemoteAddr();
        boolean allowed = switch (rateLimit.strategy()) {
            case FIXED_WINDOW -> redisRateLimiter.fixedWindow(method.toGenericString(), client,
                    rateLimit.count(), rateLimit.windowSeconds());
            case TOKEN_BUCKET -> redisRateLimiter.tokenBucket(method.toGenericString(), client,
                    rateLimit.capacity(), rateLimit.refillRate());
        };
        if (!allowed) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED, SecurityMessageConstants.RATE_LIMITED);
        }
        return invocation.proceed();
    }
}
