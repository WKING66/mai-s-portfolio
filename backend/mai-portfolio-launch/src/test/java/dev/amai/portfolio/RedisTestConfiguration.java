package dev.amai.portfolio;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import dev.amai.portfolio.redis.FixedWindowRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 普通回归使用的进程内替身；真实 Redis 行为由独立集成测试验证。 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "portfolio.test.in-memory-redis", havingValue = "true", matchIfMissing = true)
class RedisTestConfiguration {
    @Bean
    SaTokenDao testSaTokenDao() {
        return new SaTokenDaoDefaultImpl();
    }

    @Bean
    FixedWindowRateLimiter testFixedWindowRateLimiter() {
        return new InMemoryFixedWindowRateLimiter();
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

    private record Window(int count, Instant expiresAt) {
    }

}
