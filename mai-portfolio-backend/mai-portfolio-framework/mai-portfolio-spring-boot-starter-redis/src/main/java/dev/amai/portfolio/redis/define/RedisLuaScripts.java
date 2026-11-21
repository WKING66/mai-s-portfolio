package dev.amai.portfolio.redis.define;

/**
 * 项目自定义 Redis Lua 脚本的统一定义。
 *
 * <p>全部脚本兼容 Redis 3.2；参数约定在每个常量上说明，调用方负责传入正确的
 * Key 顺序、新值与毫秒 TTL。限流供登录/注册复用，会话更新供 Sa-Token DAO 使用。</p>
 */
public final class RedisLuaScripts {
    /**
     * 固定窗口计数并在首次递增时设置 TTL。
     * KEYS[1]：计数 String；ARGV[1]：窗口毫秒数；返回当前计数。
     * 后续递增不延长 TTL，额度判断由调用方完成。
     */
    public static final String RATE_ACQUIRE =
            """
        local current = redis.call('INCR', KEYS[1])
        if current == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
        end
        return current
        """;

    /**
     * KEYS[1] 为桶 Hash；ARGV 为容量、每秒速率、补满时间毫秒；返回 1 通过或 0 拒绝。
     * 使用 Redis 时间避免不同应用实例的时钟偏差；命令复制模式支持 Redis 3.2 的 TIME 后写入。
     */
    public static final String TOKEN_BUCKET_ACQUIRE = """
            redis.replicate_commands()
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + tonumber(clock[2]) / 1000
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
            local tokens = tonumber(state[1]) or capacity
            local updated = tonumber(state[2]) or now
            now = math.max(now, updated)
            tokens = math.min(capacity, tokens + (now - updated) * rate / 1000)
            local allowed = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end
            redis.call('HMSET', KEYS[1], 'tokens', tokens, 'updated', now)
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return allowed
            """;

    /**
     * 更新已存在的 String 并保留原 TTL，兼容 Redis 3.2 不支持 SET KEEPTTL 的情况。
     * KEYS[1]：目标 String；ARGV[1]：新值；不存在时返回 0，更新成功返回 1。
     * 原键无过期时间时继续永久保存；读取 TTL 与写入在同一次脚本中执行。
     */
    public static final String UPDATE_STRING_KEEP_TTL =
            """
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

    // 快照可能保留旧标记却缺少最近的轮次。仅当前 Redis 进程重建的窗口才可用于放行。
    private static final String SLIDING_WINDOW_CONTEXT = """
            redis.replicate_commands()
            local key = KEYS[1]
            local process = string.match(redis.call('INFO', 'server'), 'run_id:(%w+)')
            local marker = '!retained-window:' .. process
            local retained = redis.call('ZSCORE', key, marker)
            """;

    /**
     * KEYS[1] 为事件有序集合，成员为事件编号，分数为 Redis 时间（毫秒）。
     * ARGV 为事件编号、窗口毫秒数、限额。返回 1 放行、0 达限、-1 未初始化、-2 需要更长历史。
     * 负分数的保留窗口标记不会随事件过期。键丢失时暂停放行，避免误把丢失状态当成空窗口。
     */
    public static final String SLIDING_WINDOW_ACQUIRE = SLIDING_WINDOW_CONTEXT + """
            if not retained then
                return -1
            end
            retained = -tonumber(retained)
            local window = tonumber(ARGV[2])
            if window > retained then
                return -2
            end
            local clock = redis.call('TIME')
            local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', key, 0, now - retained)
            local cutoff = math.max(now - window, 0)
            local existing = redis.call('ZSCORE', key, ARGV[1])
            if existing and tonumber(existing) > cutoff then
                return 1
            end
            if redis.call('ZCOUNT', key, '(' .. cutoff, '+inf') >= tonumber(ARGV[3]) then
                return 0
            end
            redis.call('ZADD', key, now, ARGV[1])
            return 1
            """;

    /**
     * ARGV 为历史窗口毫秒数、是否允许初始化，以及数据库中的“事件编号、受理时间”对。
     * 保留原有 Redis 登记和较长的已有历史。初始化被禁止且标记缺失时返回 0。
     */
    public static final String SLIDING_WINDOW_RESTORE = SLIDING_WINDOW_CONTEXT + """
            if not retained then
                if ARGV[2] ~= '1' then
                    return 0
                end
                redis.call('ZREMRANGEBYSCORE', key, '-inf', '(0')
            end
            for i = 3, #ARGV, 2 do
                redis.call('ZADD', key, 'NX', ARGV[i + 1], ARGV[i])
            end
            local window = tonumber(ARGV[1])
            if not retained or -tonumber(retained) < window then
                redis.call('ZADD', key, -window, marker)
            end
            return 1
            """;

    private RedisLuaScripts() {}
}
