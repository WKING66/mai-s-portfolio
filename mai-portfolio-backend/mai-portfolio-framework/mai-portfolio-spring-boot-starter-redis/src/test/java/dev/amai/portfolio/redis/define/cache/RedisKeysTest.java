package dev.amai.portfolio.redis.define.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.amai.portfolio.redis.RedisMessageConstants;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import org.junit.jupiter.api.Test;

class RedisKeysTest {
    private final RedisKeys redisKeys =
            new RedisKeys(new RedisInfrastructureProperties("portfolio"));

    @Test
    void isolatesNamespacesAndUsesStableUtf8SubjectDigest() {
        String subjectDigest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertThat(redisKeys.rateLimit(RedisKeys.LOGIN_RATE_NAMESPACE, "abc"))
                .isEqualTo("portfolio:rate:auth:login:" + subjectDigest);
        assertThat(redisKeys.rateLimit(RedisKeys.REGISTRATION_RATE_NAMESPACE, "abc"))
                .isEqualTo("portfolio:rate:auth:register:" + subjectDigest);
        RedisKeys otherApplicationRedisKeys =
                new RedisKeys(new RedisInfrastructureProperties("other-application"));
        assertThat(otherApplicationRedisKeys.rateLimit(RedisKeys.LOGIN_RATE_NAMESPACE, "abc"))
                .isNotEqualTo(redisKeys.rateLimit(RedisKeys.LOGIN_RATE_NAMESPACE, "abc"));
    }

    @Test
    void rejectsMissingSubjectsAndMalformedNamespaces() {
        for (String subject : new String[] {null, "", " "}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> redisKeys.rateLimit("auth:login", subject))
                    .withMessage(RedisMessageConstants.SUBJECT_REQUIRED);
        }
        for (String namespace : new String[] {"auth/login", "AUTH:LOGIN", "auth: login"}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> redisKeys.rateLimit(namespace, "abc"))
                    .withMessage(RedisMessageConstants.NAMESPACE_INVALID);
        }
    }

    @Test
    void appliesNamespaceLengthLimitIncludingRatePrefix() {
        // 完整命名空间限长 96；rate: 占 5 个字符，调用方最多使用 91 个字符。
        assertThat(redisKeys.rateLimit("a".repeat(91), "abc"))
                .startsWith("portfolio:rate:" + "a".repeat(91) + ":");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> redisKeys.rateLimit("a".repeat(92), "abc"))
                .withMessage(RedisMessageConstants.NAMESPACE_INVALID);
    }

    @Test
    void roundTripsOnlyThisApplicationsSaTokenKeys() {
        String rawSessionKey = "session:account:1";
        assertThat(redisKeys.saTokenPrefix()).isEqualTo("portfolio:sa-token:");
        assertThat(redisKeys.unwrapSaToken(redisKeys.saToken(rawSessionKey)))
                .isEqualTo(rawSessionKey);
        assertThat(redisKeys.unwrapSaToken("other-application:sa-token:" + rawSessionKey))
                .isEqualTo("other-application:sa-token:" + rawSessionKey);
    }
}
