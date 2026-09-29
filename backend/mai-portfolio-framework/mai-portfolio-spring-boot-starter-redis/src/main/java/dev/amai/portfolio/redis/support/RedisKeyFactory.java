package dev.amai.portfolio.redis.support;

import dev.amai.portfolio.redis.RedisMessageConstants;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 构造统一前缀的 Redis 键，并对动态主体做不可逆摘要。 */
public final class RedisKeyFactory {
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9][a-z0-9:-]{0,95}");
    private final String keyPrefix;

    public RedisKeyFactory(RedisInfrastructureProperties properties) {
        this.keyPrefix = properties.keyPrefix();
    }

    public String namespace(String namespace) {
        validateNamespace(namespace);
        return keyPrefix + ":" + namespace;
    }

    public String subjectKey(String namespace, String subject) {
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
