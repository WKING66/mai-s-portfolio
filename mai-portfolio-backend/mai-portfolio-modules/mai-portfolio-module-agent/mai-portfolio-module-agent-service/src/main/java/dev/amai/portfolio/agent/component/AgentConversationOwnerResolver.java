package dev.amai.portfolio.agent.component;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.security.context.AccountContext;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** 识别登录账号或访客 Cookie 对应的会话所有者。 */
@Component
public class AgentConversationOwnerResolver {
    private static final String VISITOR_COOKIE = "portfolio_agent_visitor";

    public Owner resolve(HttpServletRequest request, HttpServletResponse response) {
        if (StpUtil.isLogin()) {
            return new Owner(accountOwner(), false);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (VISITOR_COOKIE.equals(cookie.getName())) {
                    try {
                        return new Owner("visitor:" + UUID.fromString(cookie.getValue()), true);
                    } catch (IllegalArgumentException error) {
                        break;
                    }
                }
            }
        }
        // Cookie 不存在时只允许初始化，不凭 URL 中的会话编号建立匿名身份。
        if (response == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "会话或执行不存在。");
        }
        UUID id = UUID.randomUUID();
        var cookie = ResponseCookie.from(VISITOR_COOKIE, id.toString())
                .httpOnly(true).secure(request.isSecure()).sameSite("Strict")
                .path("/api/agent/v1").maxAge(Duration.ofDays(7)).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return new Owner("visitor:" + id, true);
    }

    public String accountOwner() {
        return "account:" + AccountContext.currentAccount().id();
    }

    public record Owner(
            String key,

            boolean anonymous) {}
}
