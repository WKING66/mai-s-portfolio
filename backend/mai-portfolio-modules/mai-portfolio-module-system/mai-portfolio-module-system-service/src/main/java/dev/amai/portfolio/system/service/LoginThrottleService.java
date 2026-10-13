package dev.amai.portfolio.system.service;

/** 认证入口的轻量防滥用边界；登录与注册复用组件，但分别占用业务额度。 */
public interface LoginThrottleService {
    /** 为指定客户端占用一次登录校验额度；成功与失败均计数，窗口到期前不重置。 */
    void acquireLoginPermit(String clientKey);

    /** 占用独立注册窗口；不会消耗或重置该客户端的登录额度。 */
    void acquireRegistrationPermit(String clientKey);
}
