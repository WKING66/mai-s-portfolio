package dev.amai.portfolio.redis.define;

/**
 * 项目自定义 Redis Lua 脚本的统一定义。
 *
 * <p>全部脚本兼容 Redis 3.2；参数约定在每个常量上说明，调用方负责传入正确的
 * Key 顺序、毫秒时间戳与 TTL。Redisson 自带的锁脚本仍由依赖组件维护。</p>
 */
public final class RedisLuaScripts {
    /**
     * 固定窗口计数并在首次递增时设置 TTL。
     * KEYS[1]：计数 String；ARGV[1]：窗口毫秒数；返回当前计数。
     */
    public static final String RATE_ACQUIRE = """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        return current
        """;

    /**
     * 写入短时映射并仅延长容器 TTL，防止较短的新条目提前清除已有条目。
     * KEYS[1]：数据 Hash；KEYS[2]：过期索引 ZSet；
     * ARGV[1..4]：条目键、值、过期毫秒时间戳、TTL 毫秒数；返回 1。
     */
    public static final String MAP_PUT = """
        redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
        redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
        local dataTtl = redis.call('PTTL', KEYS[1])
        local expiryTtl = redis.call('PTTL', KEYS[2])
        local ttl = tonumber(ARGV[4])
        if dataTtl < ttl then
            redis.call('PEXPIRE', KEYS[1], ttl)
        end
        if expiryTtl < ttl then
            redis.call('PEXPIRE', KEYS[2], ttl)
        end
        return 1
        """;

    /**
     * 原子读取并删除有效条目，过期条目同样删除且返回 nil，保证一次性消费。
     * KEYS[1]：数据 Hash；KEYS[2]：过期索引 ZSet；
     * ARGV[1..2]：条目键、当前毫秒时间戳；返回有效条目值或 nil。
     */
    public static final String MAP_TAKE = """
        local expiresAt = redis.call('ZSCORE', KEYS[2], ARGV[1])
        if not expiresAt or tonumber(expiresAt) <= tonumber(ARGV[2]) then
            redis.call('HDEL', KEYS[1], ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            return nil
        end
        local value = redis.call('HGET', KEYS[1], ARGV[1])
        redis.call('HDEL', KEYS[1], ARGV[1])
        redis.call('ZREM', KEYS[2], ARGV[1])
        return value
        """;

    /**
     * 原子清理过期条目并统计有效条目，供挑战容量检查使用。
     * KEYS[1]：数据 Hash；KEYS[2]：过期索引 ZSet；
     * ARGV[1]：当前毫秒时间戳；返回有效条目数。
     */
    public static final String MAP_SIZE = """
        local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1])
        if #expired > 0 then
            redis.call('HDEL', KEYS[1], unpack(expired))
            redis.call('ZREM', KEYS[2], unpack(expired))
        end
        return redis.call('ZCARD', KEYS[2])
        """;

    /**
     * 更新已存在的 String 并保留原 TTL，兼容 Redis 3.2 不支持 SET KEEPTTL 的情况。
     * KEYS[1]：目标 String；ARGV[1]：新值；不存在时返回 0，更新成功返回 1。
     */
    public static final String UPDATE_STRING_KEEP_TTL = """
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

    private RedisLuaScripts() {
    }
}
