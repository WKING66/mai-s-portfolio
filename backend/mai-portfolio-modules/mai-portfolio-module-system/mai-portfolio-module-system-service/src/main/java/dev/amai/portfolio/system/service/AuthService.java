package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
import dev.amai.portfolio.system.entity.vo.RegistrationVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;

public interface AuthService {
    /** 未登录返回安全的匿名状态；已有会话只检查账号是否可用。 */
    SessionVo current();

    /** 仅接受允许的页面来源，并为新会话签发独立的写操作令牌。 */
    SessionVo login(String origin, String clientKey, LoginRequest input);

    /** 注册固定为启用的普通账号，复用凭据与限流组件，不自动建立登录态。 */
    RegistrationVo register(String origin, String clientKey, RegisterRequest input);

    /** 注销当前登录会话；调用方必须先通过写操作 CSRF 检查。 */
    void logout();

    /** 受保护请求统一检查账号状态，不把登录身份与 OWNER 角色绑定。 */
    void checkActiveAccount();
}
