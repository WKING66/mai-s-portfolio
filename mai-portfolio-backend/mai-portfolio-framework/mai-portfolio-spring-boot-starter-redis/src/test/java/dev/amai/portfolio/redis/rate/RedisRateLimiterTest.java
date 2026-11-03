package dev.amai.portfolio.redis.rate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import dev.amai.portfolio.redis.define.RedisLuaScripts;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** 适配层测试；脚本真实行为由随机命名空间的 Redis 集成测试验证。 */
class RedisRateLimiterTest {
    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RScript script = mock(RScript.class);
    private final RedisRateLimiter rateLimiter = new RedisRateLimiter(redissonClient,
            new RedisKeys(new RedisInfrastructureProperties("rate-test")));

    @Test
    void fixedWindowUsesAtomicCounterAndRejectsMissingCount() {
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.<Long>eval(eq(RScript.Mode.READ_WRITE), eq(RedisLuaScripts.RATE_ACQUIRE),
                eq(RScript.ReturnType.LONG), anyList(), eq(10000L))).thenReturn(1L, 2L, 3L, null);
        assertThat(rateLimiter.fixedWindow("public void example.login()", "127.0.0.1", 2, 10)).isTrue();
        assertThat(rateLimiter.fixedWindow("public void example.login()", "127.0.0.1", 2, 10)).isTrue();
        assertThat(rateLimiter.fixedWindow("public void example.login()", "127.0.0.1", 2, 10)).isFalse();
        assertThat(rateLimiter.fixedWindow("public void example.login()", "127.0.0.1", 2, 10)).isFalse();
    }

    @Test
    void tokenBucketUsesOnlyItsCapacityAndRateParameters() {
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.<Long>eval(eq(RScript.Mode.READ_WRITE), eq(RedisLuaScripts.TOKEN_BUCKET_ACQUIRE),
                eq(RScript.ReturnType.LONG), anyList(), eq(3), eq(0.5), eq(6000L))).thenReturn(1L, 0L);
        assertThat(rateLimiter.tokenBucket("chat", "client", 3, 0.5)).isTrue();
        assertThat(rateLimiter.tokenBucket("chat", "client", 3, 0.5)).isFalse();
    }

    @Test
    void invalidDeveloperConfigurationNeverAccessesRedis() {
        assertThatIllegalArgumentException().isThrownBy(() -> rateLimiter.fixedWindow("x", "y", 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> rateLimiter.fixedWindow("x", "y", 1, 0));
        for (double rate : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
            assertThatIllegalArgumentException().isThrownBy(() -> rateLimiter.tokenBucket("x", "y", 1, rate));
        }
        assertThatIllegalArgumentException().isThrownBy(() -> rateLimiter.tokenBucket("x", "y", 0, 1));
        verifyNoInteractions(redissonClient);
    }

    @Test
    void redisFailureIsNotTurnedIntoPermissionToProceed() {
        var failure = new IllegalStateException("Redis unavailable");
        when(redissonClient.getScript(StringCodec.INSTANCE)).thenThrow(failure);
        assertThatThrownBy(() -> rateLimiter.fixedWindow("x", "y", 1, 1)).isSameAs(failure);
        assertThatThrownBy(() -> rateLimiter.tokenBucket("x", "y", 1, 1)).isSameAs(failure);
    }
}
