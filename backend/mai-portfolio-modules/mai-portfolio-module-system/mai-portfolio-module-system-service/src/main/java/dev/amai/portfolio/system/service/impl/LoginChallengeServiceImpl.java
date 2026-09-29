package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.entity.vo.LoginChallengeVo;
import dev.amai.portfolio.system.service.LoginChallengeService;
import dev.amai.portfolio.system.service.LoginThrottleService;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
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
    // 显式指定 MGF1 SHA-256，避免 JCE 提供者默认使用 SHA-1 而与浏览器 Web Crypto 不一致。
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final ConcurrentHashMap<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final SecurityProperties security;
    private final LoginThrottleService throttle;

    public LoginChallengeServiceImpl(SecurityProperties security, LoginThrottleService throttle) {
        this.security = security;
        this.throttle = throttle;
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
        // 清理 + 容量检查 + 写入需原子执行，避免并发下超额签发
        synchronized (challenges) {
            // 惰性清理已过期的挑战条目
            Instant now = Instant.now();
            challenges.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
            // 全局未过期挑战数达上限时拒绝新请求，保护 RSA 密钥生成的 CPU 开销
            if (challenges.size() >= security.maxOutstandingChallenges()) {
                throw new ApiException(ApiErrorCode.RATE_LIMITED, SystemMessageConstants.LOGIN_CHALLENGE_BUSY);
            }
            try {
                // 每次登录生成独立密钥对，提供前向安全——私钥泄露仅影响单次登录
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(RSA_BITS, random);
                KeyPair pair = generator.generateKeyPair();
                // 挑战 ID：24 字节随机数 → Base64url（无填充），不可预测且无 URL 安全问题
                byte[] idBytes = new byte[CHALLENGE_ID_BYTES];
                random.nextBytes(idBytes);
                String id = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);
                Instant expiresAt = Instant.now().plus(security.loginChallengeTtl());
                // 仅缓存私钥；公钥返回给前端用于加密
                challenges.put(id, new Challenge(clientKey, pair.getPrivate(), expiresAt));
                return new LoginChallengeVo(id,
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    ALGORITHM_LABEL, expiresAt);
            } catch (GeneralSecurityException cryptoFailure) {
                throw new IllegalStateException(SystemMessageConstants.LOGIN_CRYPTO_UNAVAILABLE, cryptoFailure);
            }
        }
    }

    @Override
    public String consumePassword(String clientKey, String challengeId, String encryptedPassword) {
        // remove() 先于解密：过期、坏密文与重放均不能再次尝试同一把私钥。
        Challenge challenge = challenges.remove(challengeId);
        if (challenge == null || !challenge.clientKey().equals(clientKey)
                || !Instant.now().isBefore(challenge.expiresAt())) {
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

    private record Challenge(String clientKey, PrivateKey privateKey, Instant expiresAt) {
    }
}
