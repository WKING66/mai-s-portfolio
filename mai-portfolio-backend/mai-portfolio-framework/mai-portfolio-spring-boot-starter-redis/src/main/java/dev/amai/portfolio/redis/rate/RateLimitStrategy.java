package dev.amai.portfolio.redis.rate;

/** 固定窗口限制窗口内总次数；令牌桶按速率补充并允许容量范围内的短时突发。 */
public enum RateLimitStrategy {
    FIXED_WINDOW,
    TOKEN_BUCKET
}
