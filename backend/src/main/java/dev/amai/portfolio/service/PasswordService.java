package dev.amai.portfolio.service;

public interface PasswordService {
    /** 返回不可逆且带盐的密码哈希，绝不保存原始密码。 */
    String encode(String password);

    boolean matches(String password, String hash);
}
