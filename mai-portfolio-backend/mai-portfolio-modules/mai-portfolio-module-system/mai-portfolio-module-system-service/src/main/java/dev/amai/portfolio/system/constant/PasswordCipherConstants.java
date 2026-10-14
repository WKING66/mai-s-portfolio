package dev.amai.portfolio.system.constant;

/** 固定 RSA-OAEP/SHA-256 协议；容量是传输限制，不是账号类型政策。 */
public final class PasswordCipherConstants {
    public static final int RSA_BITS = 2048;
    public static final int CIPHERTEXT_BYTES = RSA_BITS / Byte.SIZE;
    private static final int SHA256_BYTES = 256 / Byte.SIZE;
    // OAEP 最大明文长度 k - 2*hLen - 2。
    public static final int MAX_PLAINTEXT_BYTES = CIPHERTEXT_BYTES - 2 * SHA256_BYTES - 2;
    private PasswordCipherConstants() { }
}
