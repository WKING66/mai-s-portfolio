package dev.amai.portfolio.security.password;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public class Argon2PasswordHasher implements PasswordHasher {
    // 显式固定 Argon2id 成本参数，避免依赖升级后默认值变化影响新密码的哈希强度。
    private final Argon2PasswordEncoder encoder =
        new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2);

    @Override
    public String encode(String password) {
        return encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String hash) {
        return encoder.matches(password, hash);
    }
}
