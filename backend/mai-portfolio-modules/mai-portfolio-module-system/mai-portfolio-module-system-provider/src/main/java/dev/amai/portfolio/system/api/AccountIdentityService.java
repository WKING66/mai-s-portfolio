package dev.amai.portfolio.system.api;

/** 跨域获取当前有效账号身份，调用方不读取会话供应商或账号表。 */
public interface AccountIdentityService {
    long currentAccountId();
}
