package dev.amai.portfolio;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import dev.amai.portfolio.common.lock.DistributedLockService;
import dev.amai.portfolio.redis.ExpiringStringMap;
import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 普通回归使用的进程内替身；真实 Redis 行为由独立集成测试验证。 */
@Configuration(proxyBeanMethods = false)
class RedisTestConfiguration {
    @Bean
    SaTokenDao testSaTokenDao() {
        return new SaTokenDaoDefaultImpl();
    }

    @Bean
    FixedWindowRateLimiter testFixedWindowRateLimiter() {
        return new InMemoryFixedWindowRateLimiter();
    }

    @Bean
    ExpiringStringMap testExpiringStringMap() {
        return new InMemoryExpiringStringMap();
    }

    @Bean
    DistributedLockService testDistributedLockService() {
        return new LocalSynchronizedLockService();
    }

    private static final class InMemoryFixedWindowRateLimiter
            implements FixedWindowRateLimiter {
        private final Map<String, Window> windows = new HashMap<>();

        @Override
        public synchronized boolean tryAcquire(String namespace, String subject,
                int limit, Duration window) {
            String key = namespace + ':' + subject;
            Instant now = Instant.now();
            Window current = windows.get(key);
            if (current == null || !now.isBefore(current.expiresAt())) {
                windows.put(key, new Window(1, now.plus(window)));
                return true;
            }
            if (current.count() >= limit) {
                return false;
            }
            windows.put(key, new Window(current.count() + 1, current.expiresAt()));
            return true;
        }

        @Override
        public synchronized void reset(String namespace, String subject) {
            windows.remove(namespace + ':' + subject);
        }
    }

    private static final class InMemoryExpiringStringMap implements ExpiringStringMap {
        private final Map<String, Value> values = new HashMap<>();

        @Override
        public synchronized void put(String namespace, String key, String value, Duration ttl) {
            values.put(namespace + ':' + key, new Value(value, Instant.now().plus(ttl)));
        }

        @Override
        public synchronized String take(String namespace, String key) {
            Value value = values.remove(namespace + ':' + key);
            return value == null || !Instant.now().isBefore(value.expiresAt())
                ? null : value.content();
        }

        @Override
        public synchronized int size(String namespace) {
            Instant now = Instant.now();
            String prefix = namespace + ':';
            values.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
            return (int) values.keySet().stream().filter(key -> key.startsWith(prefix)).count();
        }
    }

    private static final class LocalSynchronizedLockService implements DistributedLockService {
        @Override
        public synchronized <T> T execute(String lockName, Duration waitTime,
                Duration leaseTime, java.util.function.Supplier<T> operation) {
            return operation.get();
        }
    }

    private record Window(int count, Instant expiresAt) {
    }

    private record Value(String content, Instant expiresAt) {
    }
}
