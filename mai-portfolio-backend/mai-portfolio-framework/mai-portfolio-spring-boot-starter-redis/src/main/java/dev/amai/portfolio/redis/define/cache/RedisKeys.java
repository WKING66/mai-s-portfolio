package dev.amai.portfolio.redis.define.cache;

import dev.amai.portfolio.redis.RedisMessageConstants;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Redis Key 及命名空间的统一定义与生成入口。
 *
 * <p>应用前缀来自配置；限流主体使用 SHA-256 摘要，避免暴露原始客户端信息。
 * 修改已有格式会影响存量会话与短时数据，需明确迁移策略后再调整。</p>
 */
public final class RedisKeys {
    /** 登录尝试限流窗口，TTL 来自安全配置。 */
    public static final String LOGIN_RATE_NAMESPACE = "auth:login";

    /** 注册尝试的独立窗口；注册失败或防刷不能消耗正常登录额度。 */
    public static final String REGISTRATION_RATE_NAMESPACE = "auth:register";

    private static final String RATE_KEY_NAMESPACE_PREFIX = "rate:";
    private static final String SA_TOKEN_NAMESPACE_SUFFIX = ":sa-token:";
    private static final String SUBJECT_DIGEST_ALGORITHM = "SHA-256";

    /** 校验含 rate: 前缀的完整命名空间，最长 96 个字符。 */
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9:-]{0,95}");

    private final String keyPrefix;

    public RedisKeys(RedisInfrastructureProperties redisInfrastructureProperties) {
        this.keyPrefix = redisInfrastructureProperties.keyPrefix();
    }

    /** 固定窗口计数 String：{应用前缀}:rate:{命名空间}:{主体摘要}。 */
    public String rateLimit(String namespace, String subject) {
        return subjectKey(RATE_KEY_NAMESPACE_PREFIX + namespace, subject);
    }

    /** Sa-Token 管理的 token、会话及角色缓存统一前缀，TTL 由 Sa-Token 控制。 */
    public String saTokenPrefix() {
        return keyPrefix + SA_TOKEN_NAMESPACE_SUFFIX;
    }

    /** 为 Sa-Token 原始键或搜索前缀添加应用命名空间。 */
    public String saToken(String key) {
        return saTokenPrefix() + key;
    }

    /** 搜索结果去除本应用前缀，保持 Sa-Token DAO 对外返回原始键的契约。 */
    public String unwrapSaToken(String key) {
        String saTokenPrefix = saTokenPrefix();
        return key.startsWith(saTokenPrefix) ? key.substring(saTokenPrefix.length()) : key;
    }

    private String namespace(String namespace) {
        validateNamespace(namespace);
        return keyPrefix + ":" + namespace;
    }

    private String subjectKey(String namespace, String subject) {
        // 1. 拒绝空主体，避免不同匿名调用共享无意义的计数键。
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(RedisMessageConstants.SUBJECT_REQUIRED);
        }
        // 2. 校验命名空间并加入配置前缀；主体保留原值语义，只以摘要进入键名。
        return namespace(namespace) + ":" + sha256(subject);
    }

    private void validateNamespace(String namespace) {
        if (namespace == null || !NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(RedisMessageConstants.NAMESPACE_INVALID);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(SUBJECT_DIGEST_ALGORITHM);
            return HexFormat.of()
                    .formatHex(messageDigest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(RedisMessageConstants.SHA256_UNAVAILABLE, unavailable);
        }
    }
}
