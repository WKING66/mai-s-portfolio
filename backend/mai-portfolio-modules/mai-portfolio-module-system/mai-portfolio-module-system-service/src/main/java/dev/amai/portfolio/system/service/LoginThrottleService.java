package dev.amai.portfolio.system.service;

/** 管理登录入口的轻量防滥用边界，避免昂贵的密钥生成和密码校验被无限调用。 */
public interface LoginThrottleService {
    /** 为指定客户端占用一次公钥签发额度；超限时直接拒绝。 */
    void acquireChallengePermit(String clientKey);

    /** 为指定客户端占用一次登录校验额度；超限时直接拒绝。 */
    void acquireLoginPermit(String clientKey);

    /** 登录成功后清除该客户端的失败窗口，避免影响正常管理操作。 */
    void recordLoginSuccess(String clientKey);
}
