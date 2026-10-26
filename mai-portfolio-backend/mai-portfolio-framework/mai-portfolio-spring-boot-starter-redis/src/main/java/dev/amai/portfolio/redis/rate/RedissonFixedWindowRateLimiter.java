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
    private final RedissonClient redissonClient;
    private final RedisKeys redisKeys;

    public RedissonFixedWindowRateLimiter(RedissonClient redissonClient, RedisKeys redisKeys) {
        this.redissonClient = redissonClient;
        this.redisKeys = redisKeys;
    }

    @Override
    public boolean tryAcquire(String namespace, String subject, int limit, Duration window) {
        // 1. 先校验额度与窗口，再生成统一键名；无效参数不访问 Redis。
        if (limit <= 0) {
            throw new IllegalArgumentException(RedisMessageConstants.LIMIT_POSITIVE);
        }
        requirePositive(window);
        String redisKey = redisKeys.rateLimit(namespace, subject);
        // 2. 由同一 Lua 调用完成递增与首次设置 TTL，后续请求不延长窗口。
        // Redis 调用失败直接向上抛出，不退回进程内计数，也不默认放行。
        Long currentCount =
                redissonClient
                        .getScript(StringCodec.INSTANCE)
                        .eval(
                                RScript.Mode.READ_WRITE,
                                RedisLuaScripts.RATE_ACQUIRE,
                                RScript.ReturnType.LONG,
                                List.of(redisKey),
                                window.toMillis());
        // 3. 超限请求仍参与计数；没有返回计数时拒绝此次额度申请。
        return currentCount != null && currentCount <= limit;
    }

    @Override
    public void reset(String namespace, String subject) {
        // 仅删除当前命名空间与主体的窗口，不影响其他主体的额度。
        redissonClient
                .getBucket(redisKeys.rateLimit(namespace, subject), StringCodec.INSTANCE)
                .delete();
    }

    private void requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(RedisMessageConstants.DURATION_POSITIVE);
        }
    }
}
