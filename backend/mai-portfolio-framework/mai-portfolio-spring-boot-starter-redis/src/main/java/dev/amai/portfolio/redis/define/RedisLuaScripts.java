package dev.amai.portfolio.redis.define;

/**
 * 项目自定义 Redis Lua 脚本的统一定义。
 *
 * <p>全部脚本兼容 Redis 3.2；参数约定在每个常量上说明，调用方负责传入正确的
 * Key 顺序、毫秒时间戳与 TTL。只保留登录限流与会话 TTL 更新所需脚本。</p>
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
