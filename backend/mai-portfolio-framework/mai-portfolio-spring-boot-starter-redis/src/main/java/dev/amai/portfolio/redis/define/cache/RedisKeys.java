package dev.amai.portfolio.redis.define.cache;

import dev.amai.portfolio.redis.RedisMessageConstants;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Redis Key 及命名空间的统一定义与生成入口。
 *
 * <p>应用前缀来自配置；限流主体和锁名称使用 SHA-256 摘要，避免暴露原始客户端信息。
 * 修改已有格式会影响存量会话与短时数据，需明确迁移策略后再调整。</p>
 */
public final class RedisKeys {
    /** 一次性登录挑战映射，由挑战 TTL 控制存活时间。 */
    public static final String LOGIN_CHALLENGE_NAMESPACE = "auth:login:challenge";
    /** 串行执行登录挑战的容量检查与签发，锁租期由调用方提供。 */
    public static final String LOGIN_CHALLENGE_ISSUE_LOCK_NAME = "auth-login-challenge-issue";
    /** 挑战申请限流窗口，TTL 来自安全配置。 */
    public static final String CHALLENGE_RATE_NAMESPACE = "auth:challenge";
    /** 登录尝试限流窗口，TTL 来自安全配置。 */
    public static final String LOGIN_RATE_NAMESPACE = "auth:login";

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9:-]{0,95}");
    private final String keyPrefix;

    public RedisKeys(RedisInfrastructureProperties properties) {
        this.keyPrefix = properties.keyPrefix();
    }

    /** 固定窗口计数 String：{应用前缀}:rate:{命名空间}:{主体摘要}。 */
    public String rateLimit(String namespace, String subject) {
        return subjectKey("rate:" + namespace, subject);
    }

    /** Redisson 分布式锁：{应用前缀}:lock:{锁名称摘要}。 */
    public String lock(String lockName) {
        return subjectKey("lock", lockName);
    }

    /**
     * 短时映射的 Hash 与过期索引 ZSet，顺序分别对应 Lua 的 KEYS[1]、KEYS[2]。
     * 两个键的 TTL 由映射写入脚本延长至最大条目 TTL。
     */
    public List<Object> expiringMap(String namespace) {
        String prefix = namespace("map:" + namespace);
        return List.of(prefix + ":data", prefix + ":expires");
    }

    /** Sa-Token 管理的 token、会话及角色缓存统一前缀，TTL 由 Sa-Token 控制。 */
    public String saTokenPrefix() {
        return keyPrefix + ":sa-token:";
    }

    /** 为 Sa-Token 原始键或搜索前缀添加应用命名空间。 */
    public String saToken(String key) {
        return saTokenPrefix() + key;
    }

    /** 搜索结果去除本应用前缀，保持 Sa-Token DAO 对外返回原始键的契约。 */
    public String unwrapSaToken(String key) {
        String prefix = saTokenPrefix();
        return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
    }

    private String namespace(String namespace) {
        validateNamespace(namespace);
        return keyPrefix + ":" + namespace;
    }

    private String subjectKey(String namespace, String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(RedisMessageConstants.SUBJECT_REQUIRED);
        }
        return namespace(namespace) + ":" + sha256(subject);
    }

    private void validateNamespace(String namespace) {
        if (namespace == null || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(RedisMessageConstants.NAMESPACE_INVALID);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(RedisMessageConstants.SHA256_UNAVAILABLE, unavailable);
        }
    }
}
