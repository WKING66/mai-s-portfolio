package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import dev.amai.portfolio.common.lock.DistributedLockService;
import dev.amai.portfolio.redis.ExpiringStringMap;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.entity.vo.LoginChallengeVo;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.web.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.Test;

class LoginChallengeServiceImplTest {
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    @Test
    void storesChallengeOutsideProcessMemoryAndConsumesItOnce() throws Exception {
        InMemoryExpiringStringMap challenges = new InMemoryExpiringStringMap();
        LoginChallengeServiceImpl service = new LoginChallengeServiceImpl(properties(),
            mock(LoginThrottleService.class), challenges, new ImmediateLockService());

        LoginChallengeVo challenge = service.issueChallenge("127.0.0.1");
        String encryptedPassword = encrypt("owner-password", challenge.publicKey());

        assertThat(service.consumePassword("127.0.0.1", challenge.challengeId(), encryptedPassword))
            .isEqualTo("owner-password");
        assertThatThrownBy(() -> service.consumePassword(
            "127.0.0.1", challenge.challengeId(), encryptedPassword))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void consumesChallengeEvenWhenClientBindingDoesNotMatch() throws Exception {
        InMemoryExpiringStringMap challenges = new InMemoryExpiringStringMap();
        LoginChallengeServiceImpl service = new LoginChallengeServiceImpl(properties(),
            mock(LoginThrottleService.class), challenges, new ImmediateLockService());
        LoginChallengeVo challenge = service.issueChallenge("client-a");
        String encryptedPassword = encrypt("owner-password", challenge.publicKey());

        assertThatThrownBy(() -> service.consumePassword(
            "client-b", challenge.challengeId(), encryptedPassword))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.consumePassword(
            "client-a", challenge.challengeId(), encryptedPassword))
            .isInstanceOf(ApiException.class);
    }

    private String encrypt(String password, String encodedPublicKey) throws Exception {
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
            new X509EncodedKeySpec(Base64.getDecoder().decode(encodedPublicKey)));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_SHA256);
        return Base64.getEncoder().encodeToString(
            cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
    }

    private SecurityProperties properties() {
        return new SecurityProperties(List.of("http://localhost"), Duration.ofMinutes(1), 32,
            8, Duration.ofMinutes(1), 5, Duration.ofMinutes(10));
    }

    private static final class ImmediateLockService implements DistributedLockService {
        @Override
        public <T> T execute(String lockName, Duration waitTime, Duration leaseTime,
                Supplier<T> operation) {
            return operation.get();
        }
    }

    private static final class InMemoryExpiringStringMap implements ExpiringStringMap {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public void put(String namespace, String key, String value, Duration ttl) {
            values.put(namespace + ':' + key, value);
        }

        @Override
        public String take(String namespace, String key) {
            return values.remove(namespace + ':' + key);
        }

        @Override
        public int size(String namespace) {
            String prefix = namespace + ':';
            return (int) values.keySet().stream().filter(key -> key.startsWith(prefix)).count();
        }
    }
}
