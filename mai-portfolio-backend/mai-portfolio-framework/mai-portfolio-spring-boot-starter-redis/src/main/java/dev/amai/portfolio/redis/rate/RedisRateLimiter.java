package dev.amai.portfolio.redis.rate;

import dev.amai.portfolio.redis.define.RedisLuaScripts;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
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

    /** 在同一滚动窗口内重复使用事件编号时，返回原许可且不延长该事件的有效期。 */
    public SlidingWindowResult acquireSlidingWindow(String subject, String eventId, Duration window, int limit) {
        Long result = executeSlidingWindow(RedisLuaScripts.SLIDING_WINDOW_ACQUIRE, subject,
                eventId, window.toMillis(), limit);
        if (Long.valueOf(1).equals(result)) {
            return SlidingWindowResult.ALLOWED;
        }
        if (Long.valueOf(0).equals(result)) {
            return SlidingWindowResult.EXHAUSTED;
        }
        if (Long.valueOf(-1).equals(result)) {
            return SlidingWindowResult.UNINITIALIZED;
        }
        if (Long.valueOf(-2).equals(result)) {
            return SlidingWindowResult.HISTORY_REQUIRED;
        }
        throw new RedisException("Redis 返回了未知的滚动窗口状态");
    }

    /** 以毫秒时间戳补齐历史，已登记的事件保留原时间。仅在受理已经停止时才允许初始化。 */
    public boolean restoreSlidingWindow(String subject, Duration window, Map<String, Long> history, boolean initialize) {
        Object[] arguments = new Object[2 + history.size() * 2];
        arguments[0] = window.toMillis();
        arguments[1] = initialize ? 1 : 0;
        int index = 2;
        for (var event : history.entrySet()) {
            arguments[index++] = event.getKey();
            arguments[index++] = event.getValue();
        }
        return Long.valueOf(1).equals(executeSlidingWindow(
                RedisLuaScripts.SLIDING_WINDOW_RESTORE, subject, arguments));
    }

    private Long executeSlidingWindow(String script, String subject, Object... arguments) {
        String key = redisKeys.rateLimit("sliding", subject);
        try {
            // 调用方可能持有数据库事务锁。命令超时后由相同事件编号安全重试。
            return redissonClient.getScript(StringCodec.INSTANCE)
                    .<Long>evalAsync(RScript.Mode.READ_WRITE, script, RScript.ReturnType.LONG, List.of(key), arguments)
                    .get(1, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RedisException("等待 Redis 滚动窗口时被中断", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new RedisException("Redis 滚动窗口未能完成", error);
        }
    }

    public enum SlidingWindowResult {
        ALLOWED,
        EXHAUSTED,
        UNINITIALIZED,
        HISTORY_REQUIRED
    }

    private Long execute(String script, String key, Object... arguments) {
        // Redis 故障向外传播，沿用原先的失败语义，不偷偷降级为进程内限流或放行。
        return redissonClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, script,
                RScript.ReturnType.LONG, List.of(key), arguments);
    }
}
