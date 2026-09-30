package dev.amai.portfolio.redis.rate;

import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import dev.amai.portfolio.redis.RedisMessageConstants;
import dev.amai.portfolio.redis.define.RedisLuaScripts;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import java.time.Duration;
import java.util.List;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** 使用 Redis Lua 脚本原子递增并设置窗口过期时间的限流实现。 */
public class RedissonFixedWindowRateLimiter implements FixedWindowRateLimiter {
    private final RedissonClient redisson;
    private final RedisKeys keys;

    public RedissonFixedWindowRateLimiter(RedissonClient redisson, RedisKeys keys) {
        this.redisson = redisson;
        this.keys = keys;
    }

    @Override
    public boolean tryAcquire(String namespace, String subject, int limit, Duration window) {
        if (limit <= 0) {
            throw new IllegalArgumentException(RedisMessageConstants.LIMIT_POSITIVE);
        }
        requirePositive(window);
        String redisKey = keys.rateLimit(namespace, subject);
        Long count = redisson.getScript(StringCodec.INSTANCE).eval(
            RScript.Mode.READ_WRITE,
            RedisLuaScripts.RATE_ACQUIRE,
            RScript.ReturnType.LONG,
            List.of(redisKey),
            window.toMillis());
        return count != null && count <= limit;
    }

    @Override
    public void reset(String namespace, String subject) {
        redisson.getBucket(keys.rateLimit(namespace, subject), StringCodec.INSTANCE)
            .delete();
    }

    private void requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(RedisMessageConstants.DURATION_POSITIVE);
        }
    }
}
