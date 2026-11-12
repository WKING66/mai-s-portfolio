package dev.amai.portfolio.agent.component;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.API_PATH;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.IDENTIFIER_PATTERN;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import dev.amai.portfolio.security.context.AccountContext;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** 识别登录账号或访客 Cookie 对应的会话所有者。 */
@Component
public class AgentConversationOwnerResolver {
    private static final String VISITOR_COOKIE = "portfolio_agent_visitor";

    private static final Pattern VISITOR_ID = Pattern.compile(IDENTIFIER_PATTERN);
    private static final Duration VISITOR_COOKIE_LIFETIME = Duration.ofDays(7);
    private static final String VISITOR_PREFIX = "visitor:";
    private static final String ACCOUNT_PREFIX = "account:";

    public Owner resolve(HttpServletRequest request, HttpServletResponse response) {
        if (StpUtil.isLogin()) {
            return new Owner(accountOwner(), false);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (VISITOR_COOKIE.equals(cookie.getName())) {
                    String value = cookie.getValue();
                    if (value != null && VISITOR_ID.matcher(value).matches()) {
                        return new Owner(VISITOR_PREFIX + value.toLowerCase(Locale.ROOT), true);
                    }
                    break;
                }
            }
        }
        // Cookie 不存在时只允许初始化，不凭 URL 中的会话编号建立匿名身份。
        if (response == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "会话或执行不存在。");
        }
        String id = IdUtil.randomUUID();
        var cookie = ResponseCookie.from(VISITOR_COOKIE, id)
                .httpOnly(true).secure(request.isSecure()).sameSite("Strict")
                .path(API_PATH).maxAge(VISITOR_COOKIE_LIFETIME).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return new Owner(VISITOR_PREFIX + id, true);
    }

    public String accountOwner() {
        return ACCOUNT_PREFIX + AccountContext.currentAccount().id();
    }

    public record Owner(
            String key,

            boolean anonymous) {}
}
