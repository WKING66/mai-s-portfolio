package dev.amai.portfolio.security.session;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import dev.amai.portfolio.redis.define.RedisLuaScripts;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
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
    private final RedisKeys keys;

    public NamespacedSaTokenDao(RedissonClient redissonClient, RedisKeys keys) {
        super(redissonClient);
        this.keys = keys;
    }

    @Override
    public String get(String key) {
        return bucket(keys.saToken(key)).get();
    }

    @Override
    public void set(String key, String value, long timeout) {
        if (timeout == 0 || timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        RBucket<String> bucket = bucket(keys.saToken(key));
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
            RedisLuaScripts.UPDATE_STRING_KEEP_TTL,
            RScript.ReturnType.LONG,
            List.of(keys.saToken(key)),
            value);
    }

    @Override
    public void delete(String key) {
        bucket(keys.saToken(key)).delete();
    }

    @Override
    public long getTimeout(String key) {
        long remainingMillis = bucket(keys.saToken(key)).remainTimeToLive();
        return remainingMillis < 0 ? remainingMillis : remainingMillis / 1_000;
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        RBucket<String> bucket = bucket(keys.saToken(key));
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
        return super.searchData(keys.saToken(keyPrefix), keyword, start, size, sortType).stream()
            .map(keys::unwrapSaToken)
            .toList();
    }

    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(key, StringCodec.INSTANCE);
    }
}
