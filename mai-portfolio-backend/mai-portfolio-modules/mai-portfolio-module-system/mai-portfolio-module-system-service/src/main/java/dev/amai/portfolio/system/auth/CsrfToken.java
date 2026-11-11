package dev.amai.portfolio.system.auth;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;

/** CSRF 按登录 token 隔离，同时让升级前已登录的客户端继续使用旧会话令牌。 */
public final class CsrfToken {
    private CsrfToken() {}

    public static String current() {
        var tokenSession = StpUtil.getTokenSession();
        String token = tokenSession == null ? null : tokenSession.getString(AuthConstants.CSRF_SESSION_KEY);
        if (token != null) {
            return token;
        }
        return StpUtil.getSession().getString(AuthConstants.CSRF_SESSION_KEY);
    }
}
