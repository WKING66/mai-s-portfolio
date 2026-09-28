package dev.amai.portfolio.service;

import dev.amai.portfolio.dto.LoginRequest;
import dev.amai.portfolio.dto.SessionResponse;
import dev.amai.portfolio.dto.LoginChallengeResponse;

public interface AdminSessionService {
    /** 未登录返回安全的匿名状态；已有会话仍需重新确认 OWNER 权限。 */
    SessionResponse current();

    LoginChallengeResponse issueChallenge();

    /** 仅接受允许的页面来源，并为新会话签发独立的写操作令牌。 */
    SessionResponse login(String origin, LoginRequest input);

    /** 注销当前 OWNER 会话；调用方必须先通过写操作 CSRF 检查。 */
    void logout();
}
