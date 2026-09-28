package dev.amai.portfolio.service.impl;

import dev.amai.portfolio.common.ApiErrorCode;
import dev.amai.portfolio.common.ApiException;
import dev.amai.portfolio.common.MessageConstants;
import dev.amai.portfolio.config.SecurityProperties;
import dev.amai.portfolio.dto.LoginChallengeResponse;
import dev.amai.portfolio.service.LoginChallengeService;
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

    public LoginChallengeServiceImpl(SecurityProperties security) {
        this.security = security;
    }

    @Override
    public LoginChallengeResponse issueChallenge() {
        synchronized (challenges) {
            Instant now = Instant.now();
            challenges.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
            if (challenges.size() >= security.maxOutstandingChallenges()) {
                throw new ApiException(ApiErrorCode.RATE_LIMITED, MessageConstants.LOGIN_CHALLENGE_BUSY);
            }
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(RSA_BITS, random);
                KeyPair pair = generator.generateKeyPair();
                byte[] idBytes = new byte[CHALLENGE_ID_BYTES];
                random.nextBytes(idBytes);
                String id = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);
                Instant expiresAt = Instant.now().plus(security.loginChallengeTtl());
                challenges.put(id, new Challenge(pair.getPrivate(), expiresAt));
                return new LoginChallengeResponse(id,
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    ALGORITHM_LABEL, expiresAt);
            } catch (GeneralSecurityException cryptoFailure) {
                throw new IllegalStateException(MessageConstants.LOGIN_CRYPTO_UNAVAILABLE, cryptoFailure);
            }
        }
    }

    @Override
    public String consumePassword(String challengeId, String encryptedPassword) {
        // remove() 先于解密：过期、坏密文与重放均不能再次尝试同一把私钥。
        Challenge challenge = challenges.remove(challengeId);
        if (challenge == null || !Instant.now().isBefore(challenge.expiresAt())) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, MessageConstants.LOGIN_CHALLENGE_INVALID);
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
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, MessageConstants.LOGIN_CIPHERTEXT_INVALID);
    }

    private record Challenge(PrivateKey privateKey, Instant expiresAt) {
    }
}
