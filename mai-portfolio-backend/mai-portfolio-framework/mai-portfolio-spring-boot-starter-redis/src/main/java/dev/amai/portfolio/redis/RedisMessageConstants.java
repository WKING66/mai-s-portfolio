package dev.amai.portfolio.redis;

/** Redis 基础设施统一提示。 */
public final class RedisMessageConstants {
    public static final String NAMESPACE_INVALID = "Redis 命名空间格式不正确";
    public static final String SUBJECT_REQUIRED = "Redis 主体标识不能为空";
    public static final String SHA256_UNAVAILABLE = "JVM 不支持 SHA-256";

    private RedisMessageConstants() {}
}
