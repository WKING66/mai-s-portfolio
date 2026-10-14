package dev.amai.portfolio.redis;

import java.time.Duration;

/** 基于共享 Redis 状态的固定窗口限流能力。 */
public interface FixedWindowRateLimiter {
    /**
     * 尝试占用一次窗口额度；首次请求启动窗口，后续请求及超限请求均递增计数但不续期。
     *
     * <p>额度和窗口须大于零，窗口以毫秒传入 Redis。Redis 故障向调用方传播，不默认放行。
     *
     * @return {@code true} 表示已取得额度，{@code false} 表示超限或未取得有效计数
     */
    boolean tryAcquire(String namespace, String subject, int limit, Duration window);

    /** 清除指定主体的当前窗口；是否重置由业务调用方决定。 */
    void reset(String namespace, String subject);
}
