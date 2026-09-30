package dev.amai.portfolio.redis.lock;

import dev.amai.portfolio.common.lock.DistributedLockService;
import dev.amai.portfolio.common.lock.LockAcquisitionException;
import dev.amai.portfolio.redis.RedisMessageConstants;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

/** Redisson 可重入分布式锁实现。 */
public class RedissonDistributedLockService implements DistributedLockService {
    private final RedissonClient redisson;
    private final RedisKeys keys;

    public RedissonDistributedLockService(RedissonClient redisson, RedisKeys keys) {
        this.redisson = redisson;
        this.keys = keys;
    }

    @Override
    public <T> T execute(String lockName, Duration waitTime, Duration leaseTime,
            Supplier<T> operation) {
        requirePositive(waitTime);
        requirePositive(leaseTime);
        Objects.requireNonNull(operation, "operation");
        RLock lock = redisson.getLock(keys.lock(lockName));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new LockAcquisitionException(RedisMessageConstants.LOCK_ACQUISITION_TIMEOUT);
            }
            return operation.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(
                RedisMessageConstants.LOCK_ACQUISITION_INTERRUPTED, interrupted);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(RedisMessageConstants.DURATION_POSITIVE);
        }
    }
}
