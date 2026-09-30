package dev.amai.portfolio.redis.store;

import dev.amai.portfolio.redis.ExpiringStringMap;
import dev.amai.portfolio.redis.RedisMessageConstants;
import dev.amai.portfolio.redis.define.RedisLuaScripts;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import java.time.Duration;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * 使用 Redis Hash 与过期时间有序集合实现的短时字符串映射。
 *
 * <p>读写、消费与过期清理均由 Lua 原子执行，避免依赖 RMapCache
 * 的延迟清理任务，保证多实例环境下的未过期数量准确。</p>
 */
public class RedissonExpiringStringMap implements ExpiringStringMap {
    private final RedissonClient redisson;
    private final RedisKeys keys;

    public RedissonExpiringStringMap(RedissonClient redisson, RedisKeys keys) {
        this.redisson = redisson;
        this.keys = keys;
    }

    @Override
    public void put(String namespace, String key, String value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(RedisMessageConstants.DURATION_POSITIVE);
        }
        long ttlMillis = ttl.toMillis();
        script().eval(RScript.Mode.READ_WRITE, RedisLuaScripts.MAP_PUT, RScript.ReturnType.LONG,
            keys.expiringMap(namespace), key, value, System.currentTimeMillis() + ttlMillis, ttlMillis);
    }

    @Override
    public String take(String namespace, String key) {
        return script().eval(RScript.Mode.READ_WRITE, RedisLuaScripts.MAP_TAKE, RScript.ReturnType.VALUE,
            keys.expiringMap(namespace), key, System.currentTimeMillis());
    }

    @Override
    public int size(String namespace) {
        Long size = script().eval(RScript.Mode.READ_WRITE, RedisLuaScripts.MAP_SIZE, RScript.ReturnType.LONG,
            keys.expiringMap(namespace), System.currentTimeMillis());
        return size == null ? 0 : Math.toIntExact(size);
    }

    private RScript script() {
        return redisson.getScript(StringCodec.INSTANCE);
    }
}
