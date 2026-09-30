package dev.amai.portfolio.redis.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.common.lock.LockAcquisitionException;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

class RedissonDistributedLockServiceTest {
    @Test
    void executesOperationAndReleasesOwnedLock() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(100, 1_000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        RedissonDistributedLockService service = service(redisson);

        String result = service.execute("login-challenge", Duration.ofMillis(100),
            Duration.ofSeconds(1), () -> "done");

        assertThat(result).isEqualTo("done");
        verify(lock).unlock();
    }

    @Test
    void rejectsOperationWhenWaitTimeExpires() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(100, 1_000, TimeUnit.MILLISECONDS)).thenReturn(false);
        RedissonDistributedLockService service = service(redisson);

        assertThatThrownBy(() -> service.execute("login-challenge", Duration.ofMillis(100),
            Duration.ofSeconds(1), () -> "not-run"))
            .isInstanceOf(LockAcquisitionException.class);
    }

    private RedissonDistributedLockService service(RedissonClient redisson) {
        RedisKeys keys = new RedisKeys(
            new RedisInfrastructureProperties("mai-portfolio:test"));
        return new RedissonDistributedLockService(redisson, keys);
    }
}
