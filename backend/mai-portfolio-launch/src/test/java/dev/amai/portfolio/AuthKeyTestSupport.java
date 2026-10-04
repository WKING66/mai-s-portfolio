package dev.amai.portfolio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 测试进程生成唯一临时密钥；只用于测试，私钥不进入 Git 或控制台。 */
abstract class AuthKeyTestSupport {
    static final KeyPair KEY_PAIR;
    private static final Path PRIVATE_KEY_FILE;

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEY_PAIR = generator.generateKeyPair();
            Path directory = Files.createTempDirectory("portfolio-auth-test-");
            PRIVATE_KEY_FILE = directory.resolve("private.pem");
            Files.writeString(PRIVATE_KEY_FILE, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(KEY_PAIR.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.deleteIfExists(PRIVATE_KEY_FILE);
                    Files.deleteIfExists(directory);
                } catch (java.io.IOException ignored) {
                    // 仅测试临时目录；不把密钥内容打印到日志。
                }
            }));
        } catch (Exception error) {
            throw new ExceptionInInitializerError("无法初始化认证测试密钥");
        }
    }

    @DynamicPropertySource
    static void fixedRsaKey(DynamicPropertyRegistry registry) {
        registry.add("portfolio.security.rsa-private-key", () -> PRIVATE_KEY_FILE.toUri().toString());
        // 大量回归共享回环地址；真实分布式限流由双实例测试使用原始阈值单独验证。
        registry.add("portfolio.security.max-login-attempts-per-client", () -> 256);
    }

    static String privateKeyLocation() {
        return PRIVATE_KEY_FILE.toUri().toString();
    }
}
