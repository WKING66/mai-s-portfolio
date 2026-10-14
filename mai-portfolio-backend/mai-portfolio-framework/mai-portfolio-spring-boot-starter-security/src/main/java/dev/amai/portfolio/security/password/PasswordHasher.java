package dev.amai.portfolio.security.password;

/** 密码散列边界；业务调用方通过此接口编码和匹配，不直接依赖具体算法。 */
public interface PasswordHasher {
    /** 返回不可逆且带盐的密码哈希，绝不保存原始密码。 */
    String encode(String password);

    /** 使用原始密码核对已存储的哈希；失败不应改写账号中的原哈希。 */
    boolean matches(String password, String hash);
}
