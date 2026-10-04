package dev.amai.portfolio.system.service;

/** 管理登录入口的轻量防滥用边界，避免昂贵的 RSA 解密和密码校验被无限调用。 */
public interface LoginThrottleService {
    /** 为指定客户端占用一次登录校验额度；成功与失败均计数，窗口到期前不重置。 */
    void acquireLoginPermit(String clientKey);
}
