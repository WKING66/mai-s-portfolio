package dev.amai.portfolio.redis;

import java.time.Duration;

/** 基于共享 Redis 状态的固定窗口限流能力。 */
public interface FixedWindowRateLimiter {
    /**
     * 尝试占用一次窗口额度。
     *
     * @return {@code true} 表示已取得额度，{@code false} 表示超过限制
     */
    boolean tryAcquire(String namespace, String subject, int limit, Duration window);

    /** 清除指定主体的当前窗口，通常用于成功认证后重置失败计数。 */
    void reset(String namespace, String subject);
}
