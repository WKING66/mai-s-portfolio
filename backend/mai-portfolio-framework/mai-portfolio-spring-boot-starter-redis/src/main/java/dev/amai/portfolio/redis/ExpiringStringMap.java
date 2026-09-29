package dev.amai.portfolio.redis;

import java.time.Duration;

/** 支持逐条过期与原子取出的 Redis 字符串映射。 */
public interface ExpiringStringMap {
    /** 写入一个带生存时间的条目。 */
    void put(String namespace, String key, String value, Duration ttl);

    /**
     * 原子删除并返回条目。
     *
     * <p>该语义适合一次性凭证，避免多实例并发消费同一条数据。</p>
     */
    String take(String namespace, String key);

    /** 返回当前未过期条目数。 */
    int size(String namespace);
}
