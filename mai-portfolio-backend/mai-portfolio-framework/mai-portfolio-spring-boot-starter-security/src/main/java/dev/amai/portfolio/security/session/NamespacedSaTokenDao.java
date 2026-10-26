package dev.amai.portfolio.security.session;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoForRedisson;
import dev.amai.portfolio.redis.define.RedisLuaScripts;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    private final RedisKeys redisKeys;

    public NamespacedSaTokenDao(RedissonClient redissonClient, RedisKeys redisKeys) {
        super(redissonClient);
        this.redisKeys = redisKeys;
    }

    @Override
    public String get(String key) {
        return stringBucket(redisKeys.saToken(key)).get();
    }

    @Override
    public void set(String key, String value, long timeout) {
        // 1. 沿用 Sa-Token 的秒级有效期约定；零和无效负值不写入 Redis。
        if (timeout == 0 || timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        // 2. 使用同一 StringCodec 和应用前缀；永久保存与带 TTL 写入分别处理。
        RBucket<String> redissonBucket = stringBucket(redisKeys.saToken(key));
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            redissonBucket.set(value);
            return;
        }
        redissonBucket.set(value, Duration.ofSeconds(timeout));
    }

    @Override
    public void update(String key, String value) {
        // Lua 在单次操作中更新值并保留 TTL；键不存在时不重新创建会话。
        // Redis 异常交由调用方处理，不以本地会话替代持久化失败。
        redissonClient
                .getScript(StringCodec.INSTANCE)
                .eval(
                        RScript.Mode.READ_WRITE,
                        RedisLuaScripts.UPDATE_STRING_KEEP_TTL,
                        RScript.ReturnType.LONG,
                        List.of(redisKeys.saToken(key)),
                        value);
    }

    @Override
    public void delete(String key) {
        stringBucket(redisKeys.saToken(key)).delete();
    }

    @Override
    public long getTimeout(String key) {
        long remainingMillis = stringBucket(redisKeys.saToken(key)).remainTimeToLive();
        // 保留永久/不存在的负值标记；只有非负 TTL 才从毫秒换算为整秒。
        return remainingMillis < 0
                ? remainingMillis
                : TimeUnit.MILLISECONDS.toSeconds(remainingMillis);
    }

    @Override
    public void updateTimeout(String key, long timeout) {
        // 1. 先定位同一命名空间的键；永久保存只清除 TTL，不重写其值。
        RBucket<String> redissonBucket = stringBucket(redisKeys.saToken(key));
        if (timeout == SaTokenDao.NEVER_EXPIRE) {
            redissonBucket.clearExpire();
            return;
        }
        // 2. 无效负值保持原状；非负值按秒更新 TTL，零沿用立即过期语义。
        if (timeout <= SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        redissonBucket.expire(Duration.ofSeconds(timeout));
    }

    @Override
    public List<String> searchData(
            String keyPrefix, String keyword, int start, int size, boolean sortType) {
        // 先限定本应用，再沿用 SDK 的遍历顺序/反转与分页，最后还原对外键名。
        return super.searchData(redisKeys.saToken(keyPrefix), keyword, start, size, sortType)
                .stream()
                .map(redisKeys::unwrapSaToken)
                .toList();
    }

    private RBucket<String> stringBucket(String key) {
        return redissonClient.getBucket(key, StringCodec.INSTANCE);
    }
}
