package dev.amai.portfolio.system.manager;

import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.config.SecurityProperties;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import org.springframework.core.io.ByteArrayResource;

/** 使用测试生成的密钥构造真实凭据组件，不读取维护者私钥。 */
public final class CredentialTestSupport {
    private static final byte[] PRIVATE_KEY = privateKey();

    private CredentialTestSupport() {}

    public static CredentialManager create(PasswordHasher passwordHasher) {
        return new CredentialManager(new SecurityProperties(
                List.of("https://example.test", "http://127.0.0.1:3004", "http://localhost"),
                new ByteArrayResource(PRIVATE_KEY), false), passwordHasher);
    }

    private static byte[] privateKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder().encode(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
