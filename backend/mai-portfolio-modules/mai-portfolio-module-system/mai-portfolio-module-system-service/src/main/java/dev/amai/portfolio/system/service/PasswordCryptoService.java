package dev.amai.portfolio.system.service;

/** 只负责密码密文解密，不管理挑战、会话、用户或限流状态。 */
public interface PasswordCryptoService {
    String decryptPassword(String encryptedPassword);
}
