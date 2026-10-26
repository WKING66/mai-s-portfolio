package dev.amai.portfolio.redis.rate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.redis.RedisMessageConstants;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import dev.amai.portfolio.redis.define.RedisLuaScripts;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** 验证限流适配层契约，不代替 Lua 在真实 Redis 中的原子性验收。 */
class RedissonFixedWindowRateLimiterTest {
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RScript redissonScript = mock(RScript.class);
    private final RedisKeys redisKeys =
            new RedisKeys(new RedisInfrastructureProperties("portfolio"));
    private final RedissonFixedWindowRateLimiter fixedWindowRateLimiter =
            new RedissonFixedWindowRateLimiter(redissonClient, redisKeys);
    private final Duration window = Duration.ofSeconds(10);

    @Test
    void acceptsCountsWithinLimitAndRejectsExcessOrMissingCount() {
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(redissonScript);
        when(redissonScript.<Long>eval(
                        RScript.Mode.READ_WRITE,
                        RedisLuaScripts.RATE_ACQUIRE,
                        RScript.ReturnType.LONG,
                        List.of(redisKeys.rateLimit("auth:login", "abc")),
                        window.toMillis()))
                .thenReturn(1L, 2L, 3L, null);

        assertThat(fixedWindowRateLimiter.tryAcquire("auth:login", "abc", 2, window)).isTrue();
        assertThat(fixedWindowRateLimiter.tryAcquire("auth:login", "abc", 2, window)).isTrue();
        assertThat(fixedWindowRateLimiter.tryAcquire("auth:login", "abc", 2, window)).isFalse();
        assertThat(fixedWindowRateLimiter.tryAcquire("auth:login", "abc", 2, window)).isFalse();
    }

    @Test
    void rejectsInvalidLimitsBeforeAccessingRedis() {
        for (int limit : new int[] {0, -1}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    fixedWindowRateLimiter.tryAcquire(
                                            "auth:login", "abc", limit, window))
                    .withMessage(RedisMessageConstants.LIMIT_POSITIVE);
        }
        verifyNoInteractions(redissonClient);
    }

    @Test
    void rejectsMissingOrNonPositiveWindowsBeforeAccessingRedis() {
        for (Duration invalidWindow : new Duration[] {null, Duration.ZERO, Duration.ofMillis(-1)}) {
            assertThatIllegalArgumentException()
                    .isThrownBy(
                            () ->
                                    fixedWindowRateLimiter.tryAcquire(
                                            "auth:login", "abc", 2, invalidWindow))
                    .withMessage(RedisMessageConstants.DURATION_POSITIVE);
        }
        verifyNoInteractions(redissonClient);
    }

    @Test
    void rejectsInvalidKeysBeforeAccessingRedis() {
        assertThatIllegalArgumentException()
                .isThrownBy(
                        () -> fixedWindowRateLimiter.tryAcquire("auth/login", "abc", 2, window));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> fixedWindowRateLimiter.tryAcquire("auth:login", " ", 2, window));
        verifyNoInteractions(redissonClient);
    }

    @Test
    void propagatesRedisFailureWithoutGrantingPermit() {
        IllegalStateException redisFailure = new IllegalStateException("Redis unavailable");
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenThrow(redisFailure);
        assertThatThrownBy(() -> fixedWindowRateLimiter.tryAcquire("auth:login", "abc", 2, window))
                .isSameAs(redisFailure);
    }

    @Test
    void resetsOnlyTheSpecifiedSubjectsWindowAndPropagatesFailure() {
        RBucket<?> redissonBucket = mock(RBucket.class);
        doReturn(redissonBucket)
                .when(redissonClient)
                .getBucket(redisKeys.rateLimit("auth:login", "abc"), StringCodec.INSTANCE);
        fixedWindowRateLimiter.reset("auth:login", "abc");
        verify(redissonBucket).delete();

        IllegalStateException redisFailure = new IllegalStateException("Redis delete failed");
        when(redissonBucket.delete()).thenThrow(redisFailure);
        assertThatThrownBy(() -> fixedWindowRateLimiter.reset("auth:login", "abc"))
                .isSameAs(redisFailure);
    }
}
