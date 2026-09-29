package dev.amai.portfolio.security.session;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import java.time.Duration;
import java.util.List;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * 为 Sa-Token 的全部 Redis 键增加应用级命名空间。
 *
 * <p>同一 Redis 实例可被多个应用复用，而不会发生 token 或会话键冲突。</p>
 */
public final class NamespacedSaTokenDao extends SaTokenDaoForRedisson {
    private static final String UPDATE_KEEP_TTL_SCRIPT = """
        local ttl = redis.call('PTTL', KEYS[1])
        if ttl == -2 then
            return 0
        end
        redis.call('SET', KEYS[1], ARGV[1])
        if ttl >= 0 then
            redis.call('PEXPIRE', KEYS[1], ttl)
        end
        return 1
        """;

    private final String prefix;

    public NamespacedSaTokenDao(RedissonClient redissonClient, String keyPrefix) {
        super(redissonClient);
        this.prefix = keyPrefix + ":sa-token:";
    }

    @Override
    public String get(String key) {
        return bucket(wrap(key)).get();
    }

    @Override
    public void set(String key, String value, long timeout) {
        if (timeout == 0 || timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        RBucket<String> bucket = bucket(wrap(key));
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            bucket.set(value);
            return;
        }
        bucket.set(value, Duration.ofSeconds(timeout));
    }

    @Override
    public void update(String key, String value) {
        redissonClient.getScript(StringCodec.INSTANCE).eval(
            RScript.Mode.READ_WRITE,
            UPDATE_KEEP_TTL_SCRIPT,
            RScript.ReturnType.LONG,
            List.of(wrap(key)),
            value);
    }

    @Override
    public void delete(String key) {
        bucket(wrap(key)).delete();
    }

    @Override
    public long getTimeout(String key) {
        long remainingMillis = bucket(wrap(key)).remainTimeToLive();
        return remainingMillis < 0 ? remainingMillis : remainingMillis / 1_000;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        RBucket<String> bucket = bucket(wrap(key));
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            bucket.clearExpire();
            return;
        }
        if (timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        bucket.expire(Duration.ofSeconds(timeout));
    }

    @Override
    public List<String> searchData(String keyPrefix, String keyword, int start,
            int size, boolean sortType) {
        return super.searchData(wrap(keyPrefix), keyword, start, size, sortType).stream()
            .map(this::unwrap)
            .toList();
    }

    private String wrap(String key) {
        return prefix + key;
    }

    private String unwrap(String key) {
        return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
    }

    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(key, StringCodec.INSTANCE);
    }
}
