package dev.amai.portfolio.security.password;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** 使用固定成本的 Argon2id 编码器；匹配已有哈希时由编码器读取哈希中保存的参数。 */
public class Argon2PasswordHasher implements PasswordHasher {
    private static final int SALT_LENGTH_BYTES = 16;
    private static final int HASH_LENGTH_BYTES = 32;
    private static final int PARALLELISM = 1;
    private static final int MEMORY_COST_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;
    // 显式固定 Argon2id 成本参数，避免依赖升级后默认值变化影响新密码的哈希强度。
    private final Argon2PasswordEncoder argon2PasswordEncoder =
            new Argon2PasswordEncoder(
                    SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_COST_KIB, ITERATIONS);

    @Override
    public String encode(String password) {
        return argon2PasswordEncoder.encode(password);
    }

    @Override
    public boolean matches(String password, String hash) {
        return argon2PasswordEncoder.matches(password, hash);
    }
}
