package dev.amai.portfolio.redis.store;

import dev.amai.portfolio.redis.ExpiringStringMap;
import dev.amai.portfolio.redis.RedisMessageConstants;
import dev.amai.portfolio.redis.support.RedisKeyFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** Redisson 逐条过期字符串映射实现。 */
public class RedissonExpiringStringMap implements ExpiringStringMap {
    private final RedissonClient redisson;
    private final RedisKeyFactory keys;

    public RedissonExpiringStringMap(RedissonClient redisson, RedisKeyFactory keys) {
        this.redisson = redisson;
        this.keys = keys;
    }

    @Override
    public void put(String namespace, String key, String value, Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(RedisMessageConstants.DURATION_POSITIVE);
        }
        map(namespace).put(key, value, ttl.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public String take(String namespace, String key) {
        return map(namespace).remove(key);
    }

    @Override
    public int size(String namespace) {
        return map(namespace).size();
    }

    private RMapCache<String, String> map(String namespace) {
        return redisson.getMapCache(keys.namespace("map:" + namespace), StringCodec.INSTANCE);
    }
}
