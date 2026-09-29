package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.common.lock.DistributedLockService;
import dev.amai.portfolio.common.lock.LockAcquisitionException;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.entity.vo.LoginChallengeVo;
import dev.amai.portfolio.system.service.LoginChallengeService;
import dev.amai.portfolio.system.service.LoginThrottleService;
import dev.amai.portfolio.redis.ExpiringStringMap;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.springframework.stereotype.Service;

@Service
public class LoginChallengeServiceImpl implements LoginChallengeService {
    private static final int RSA_BITS = 2048;
    private static final int RSA_CIPHERTEXT_BYTES = RSA_BITS / Byte.SIZE;
    private static final int CHALLENGE_ID_BYTES = 24;
    private static final String ALGORITHM_LABEL = "RSA-OAEP-256";
    private static final String CHALLENGE_NAMESPACE = "auth:login:challenge";
    private static final String CHALLENGE_ISSUE_LOCK = "auth-login-challenge-issue";
    private static final Duration LOCK_WAIT_TIME = Duration.ofMillis(500);
    private static final Duration LOCK_LEASE_TIME = Duration.ofSeconds(10);
    private static final char PAYLOAD_SEPARATOR = '.';
    // 显式指定 MGF1 SHA-256，避免 JCE 提供者默认使用 SHA-1 而与浏览器 Web Crypto 不一致。
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final SecureRandom random = new SecureRandom();
    private final SecurityProperties security;
    private final LoginThrottleService throttle;
    private final ExpiringStringMap challenges;
    private final DistributedLockService locks;

    public LoginChallengeServiceImpl(SecurityProperties security, LoginThrottleService throttle,
            ExpiringStringMap challenges, DistributedLockService locks) {
        this.security = security;
        this.throttle = throttle;
        this.challenges = challenges;
        this.locks = locks;
    }

    /**
     * 为指定客户端签发一次性的 RSA 加密挑战。
     * <p>前端拿到公钥后用 Web Crypto API 加密密码，再连同 challengeId 一起回传，
     * 后端用此处缓存的私钥解密，从而避免密码明文出现在网络上。</p>
     *
     * @param clientKey 客户端标识，用于限流与绑定挑战归属
     * @return 包含 challengeId、Base64 公钥、算法标签和过期时间的 VO
     */
    @Override
    public LoginChallengeVo issueChallenge(String clientKey) {
        // 限流：防止同一客户端频繁申请挑战，消耗服务端 CPU
        throttle.acquireChallengePermit(clientKey);
        try {
            // 容量检查和写入通过分布式锁串行化，避免多实例并发突破上限。
            return locks.execute(CHALLENGE_ISSUE_LOCK, LOCK_WAIT_TIME, LOCK_LEASE_TIME,
                () -> createChallenge(clientKey));
        } catch (LockAcquisitionException busy) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED,
                SystemMessageConstants.LOGIN_CHALLENGE_BUSY);
        }
    }

    @Override
    public String consumePassword(String clientKey, String challengeId, String encryptedPassword) {
        // remove() 先于解密：过期、坏密文与重放均不能再次尝试同一把私钥。
        String payload = challenges.take(CHALLENGE_NAMESPACE, challengeId);
        Challenge challenge = decodeChallenge(payload);
        if (challenge == null || !challenge.clientFingerprint().equals(clientFingerprint(clientKey))) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, SystemMessageConstants.LOGIN_CHALLENGE_INVALID);
        }
        byte[] ciphertext;
        try {
            ciphertext = Base64.getDecoder().decode(encryptedPassword);
        } catch (IllegalArgumentException invalidBase64) {
            throw invalidCiphertext();
        }
        if (ciphertext.length != RSA_CIPHERTEXT_BYTES) {
            throw invalidCiphertext();
        }
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, challenge.privateKey(), OAEP_SHA256);
            plaintext = cipher.doFinal(ciphertext);
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext)).toString();
        } catch (GeneralSecurityException | CharacterCodingException invalidCipher) {
            throw invalidCiphertext();
        } finally {
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private ApiException invalidCiphertext() {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, SystemMessageConstants.LOGIN_CIPHERTEXT_INVALID);
    }

    private LoginChallengeVo createChallenge(String clientKey) {
        if (challenges.size(CHALLENGE_NAMESPACE) >= security.maxOutstandingChallenges()) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED,
                SystemMessageConstants.LOGIN_CHALLENGE_BUSY);
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_BITS, random);
            KeyPair pair = generator.generateKeyPair();
            byte[] idBytes = new byte[CHALLENGE_ID_BYTES];
            random.nextBytes(idBytes);
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);
            Instant expiresAt = Instant.now().plus(security.loginChallengeTtl());
            challenges.put(CHALLENGE_NAMESPACE, id,
                encodeChallenge(clientKey, pair.getPrivate()), security.loginChallengeTtl());
            return new LoginChallengeVo(id,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                ALGORITHM_LABEL, expiresAt);
        } catch (GeneralSecurityException cryptoFailure) {
            throw new IllegalStateException(
                SystemMessageConstants.LOGIN_CRYPTO_UNAVAILABLE, cryptoFailure);
        }
    }

    private String encodeChallenge(String clientKey, PrivateKey privateKey) {
        String encodedPrivateKey = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        return clientFingerprint(clientKey) + PAYLOAD_SEPARATOR + encodedPrivateKey;
    }

    private Challenge decodeChallenge(String payload) {
        if (payload == null) {
            return null;
        }
        int separator = payload.indexOf(PAYLOAD_SEPARATOR);
        if (separator <= 0 || separator == payload.length() - 1) {
            return null;
        }
        byte[] encodedPrivateKey = null;
        try {
            encodedPrivateKey = Base64.getDecoder().decode(payload.substring(separator + 1));
            PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(encodedPrivateKey));
            return new Challenge(payload.substring(0, separator), privateKey);
        } catch (GeneralSecurityException | IllegalArgumentException invalidPayload) {
            return null;
        } finally {
            if (encodedPrivateKey != null) {
                Arrays.fill(encodedPrivateKey, (byte) 0);
            }
        }
    }

    /** Redis 中只保存客户端标识摘要，避免短时凭证泄露原始 IP 等信息。 */
    private String clientFingerprint(String clientKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                digest.digest(clientKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                SystemMessageConstants.LOGIN_CRYPTO_UNAVAILABLE, unavailable);
        }
    }

    private record Challenge(String clientFingerprint, PrivateKey privateKey) {
    }
}
