package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

class PasswordCryptoServiceImplTest {
    private static KeyPair keys;
    private static SecurityProperties properties;
    private static PasswordCryptoServiceImpl crypto;

    @BeforeAll
    static void setUp() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----";
        properties = new SecurityProperties(List.of("http://localhost"),
            new ByteArrayResource(pem.getBytes(StandardCharsets.US_ASCII)), false, 5, Duration.ofMinutes(10));
        crypto = new PasswordCryptoServiceImpl(properties);
    }

    private String encrypt(byte[] plaintext, MGF1ParameterSpec mgf) throws Exception {
        var cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keys.getPublic(),
            new OAEPParameterSpec("SHA-256", "MGF1", mgf, PSource.PSpecified.DEFAULT));
        return Base64.getEncoder().encodeToString(cipher.doFinal(plaintext));
    }

    @Test
    void sameFixedKeyWorksAcrossInstancesAndConcurrentRequests() throws Exception {
        String password = "中文-password-测试";
        String ciphertext = encrypt(password.getBytes(StandardCharsets.UTF_8), MGF1ParameterSpec.SHA256);
        var secondInstance = new PasswordCryptoServiceImpl(properties);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<String>> requests = IntStream.range(0, 24)
                .mapToObj(i -> (Callable<String>) () ->
                    (i % 2 == 0 ? crypto : secondInstance).decryptPassword(ciphertext)).toList();
            for (var result : executor.invokeAll(requests)) {
                assertThat(result.get()).isEqualTo(password);
            }
        }
    }

    @Test
    void acceptsOaepMaximumLength() throws Exception {
        String password = "a".repeat(190);
        assertThat(crypto.decryptPassword(encrypt(password.getBytes(StandardCharsets.UTF_8),
            MGF1ParameterSpec.SHA256))).isEqualTo(password);
    }

    @Test
    void rejectsMalformedBase64WrongLengthRandomCiphertextAndNull() {
        for (String ciphertext : List.of("!", "a".repeat(4096),
                Base64.getEncoder().encodeToString(new byte[256]))) {
            assertInvalid(ciphertext);
        }
        assertInvalid(null);
    }

    @Test
    void rejectsWrongOaepHashAndMalformedUtf8() throws Exception {
        assertInvalid(encrypt("secret".getBytes(StandardCharsets.UTF_8), MGF1ParameterSpec.SHA1));
        assertInvalid(encrypt(new byte[] {(byte) 0xc3, (byte) 0x28}, MGF1ParameterSpec.SHA256));
        var cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keys.getPublic());
        assertInvalid(Base64.getEncoder().encodeToString(cipher.doFinal(new byte[] {1, 2})));
    }

    @Test
    void invalidPrivateKeyFailsStartupWithoutExposingMaterial() {
        var invalid = new SecurityProperties(List.of("http://localhost"),
            new ByteArrayResource("invalid-secret-material".getBytes(StandardCharsets.UTF_8)),
            true, 5, Duration.ofMinutes(10));
        assertThatThrownBy(() -> new PasswordCryptoServiceImpl(invalid))
            .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("invalid-secret-material");
    }

    private void assertInvalid(String ciphertext) {
        assertThatThrownBy(() -> crypto.decryptPassword(ciphertext))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.AUTH_INVALID_CREDENTIAL_PAYLOAD));
    }
}
