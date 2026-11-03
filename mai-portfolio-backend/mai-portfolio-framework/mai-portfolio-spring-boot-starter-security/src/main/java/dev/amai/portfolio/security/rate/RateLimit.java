package dev.amai.portfolio.security.rate;

import dev.amai.portfolio.redis.rate.RateLimitStrategy;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * HTTP 方法限流，默认每个接口、每个客户端 IP 在 300 秒内最多 5 次。
 * 固定窗口只使用 count/windowSeconds；令牌桶只使用 capacity/refillRate（每秒补充令牌数）。
 * 接口身份从目标方法取得，调用方无需维护 key 或错误提示；超限交由全局异常处理。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    RateLimitStrategy strategy() default RateLimitStrategy.FIXED_WINDOW;

    int count() default 5;

    int windowSeconds() default 300;

    int capacity() default 5;

    double refillRate() default 1;
}
