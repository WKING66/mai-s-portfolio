package dev.amai.portfolio.redis.rate;

import dev.amai.portfolio.redis.define.RedisLuaScripts;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import java.util.List;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** Redis 原子限流的唯一执行入口；不持有进程内计数，不查询数据库。 */
public class RedisRateLimiter {
    private final RedissonClient redissonClient;
    private final RedisKeys redisKeys;

    public RedisRateLimiter(RedissonClient redissonClient, RedisKeys redisKeys) {
        this.redissonClient = redissonClient;
        this.redisKeys = redisKeys;
    }

    /** 首次请求开启固定窗口，后续请求及超限请求都不延长窗口。 */
    public boolean fixedWindow(String endpoint, String client, int count, int windowSeconds) {
        // 这是开发者声明的限流配置，非法值属于部署错误，不能伪装成用户参数错误或直接放行。
        if (count <= 0 || windowSeconds <= 0) {
            throw new IllegalArgumentException("固定窗口的次数和秒数必须大于零");
        }
        String key = redisKeys.rateLimit("fixed", endpoint + ":" + client + ":" + count + ":" + windowSeconds);
        Long current = execute(RedisLuaScripts.RATE_ACQUIRE, key, windowSeconds * 1000L);
        return current != null && current <= count;
    }

    /** 桶初始为满，按 Redis 时间连续补充；拒绝请求不消耗未来令牌。 */
    public boolean tokenBucket(String endpoint, String client, int capacity, double refillRate) {
        if (capacity <= 0 || !Double.isFinite(refillRate) || refillRate <= 0) {
            throw new IllegalArgumentException("令牌桶容量和每秒补充速率必须为有限正数");
        }
        double refillMillis = Math.ceil(capacity / refillRate * 1000);
        if (!Double.isFinite(refillMillis) || refillMillis > Long.MAX_VALUE) {
            throw new IllegalArgumentException("令牌桶补满时间超出可支持范围");
        }
        // 空闲到足以补满后可安全删除状态；新请求重新以满桶开始，不会凭空多发额度。
        long ttl = Math.max(1L, (long) refillMillis);
        String key = redisKeys.rateLimit("bucket", endpoint + ":" + client + ":" + capacity + ":" + refillRate);
        Long allowed = execute(RedisLuaScripts.TOKEN_BUCKET_ACQUIRE, key, capacity, refillRate, ttl);
        return Long.valueOf(1).equals(allowed);
    }

    private Long execute(String script, String key, Object... arguments) {
        // Redis 故障向外传播，沿用原先的失败语义，不偷偷降级为进程内限流或放行。
        return redissonClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, script,
                RScript.ReturnType.LONG, List.of(key), arguments);
    }
}
