package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
import dev.amai.portfolio.system.entity.vo.RegistrationVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;

public interface AuthService {
    /** 未登录返回安全的匿名状态；已有身份从 Redis 会话读取。 */
    SessionVo current();

    /** 仅接受允许的页面来源，并为新会话签发独立的写操作令牌。 */
    SessionVo login(String origin, LoginRequest input);

    /** 注册固定为启用的普通账号，复用凭据处理，不自动建立登录态；请求限流由入口执行。 */
    RegistrationVo register(String origin, RegisterRequest input);

    /** 注销当前登录会话；调用方必须先通过写操作 CSRF 检查。 */
    void logout();

}
