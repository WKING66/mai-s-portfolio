package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.entity.vo.LoginChallengeVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;

public interface AdminSessionService {
    /** 未登录返回安全的匿名状态；已有会话仍需重新确认 OWNER 权限。 */
    SessionVo current();

    LoginChallengeVo issueChallenge(String clientKey);

    /** 仅接受允许的页面来源，并为新会话签发独立的写操作令牌。 */
    SessionVo login(String origin, String clientKey, LoginRequest input);

    /** 注销当前 OWNER 会话；调用方必须先通过写操作 CSRF 检查。 */
    void logout();
}
