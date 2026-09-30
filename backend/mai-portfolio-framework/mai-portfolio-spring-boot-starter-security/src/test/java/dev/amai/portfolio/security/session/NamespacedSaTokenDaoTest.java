package dev.amai.portfolio.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.dao.SaTokenDao;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureProperties;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class NamespacedSaTokenDaoTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void prefixesSaTokenKeysBeforeReadingAndWritingRedis() {
        RedissonClient redisson = mock(RedissonClient.class);
        RBucket bucket = mock(RBucket.class);
        when(redisson.getBucket(
            "mai-portfolio:sa-token:portfolio_session:token:abc", StringCodec.INSTANCE))
            .thenReturn(bucket);
        when(bucket.get()).thenReturn("account-1");
        NamespacedSaTokenDao dao = new NamespacedSaTokenDao(redisson,
            new RedisKeys(new RedisInfrastructureProperties("mai-portfolio")));

        dao.set("portfolio_session:token:abc", "account-1", SaTokenDao.NEVER_EXPIRE);

        verify(bucket).set("account-1");
        assertThat(dao.get("portfolio_session:token:abc")).isEqualTo("account-1");
    }
}
