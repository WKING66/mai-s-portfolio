package dev.amai.portfolio.auth;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.common.AuthConstants;
import dev.amai.portfolio.common.ApiErrorCode;
import dev.amai.portfolio.common.ApiException;
import dev.amai.portfolio.common.MessageConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Component
public class AdminAuthorization implements HandlerInterceptor, WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(AuthConstants.ADMIN_PATH_PATTERN);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        // 会话查询和登录是建立管理会话的入口；其余 /admin/** 请求必须先验证当前 OWNER。
        if (path.equals(AuthConstants.LOGIN_CHALLENGE_PATH) && method.equals("GET")) {
            return true;
        }
        if (path.equals(AuthConstants.ADMIN_SESSION_PATH)
                && (method.equals("GET") || method.equals("POST"))) {
            return true;
        }
        StpUtil.checkLogin();
        StpUtil.checkRole(AuthConstants.OWNER_ROLE);
        if (!method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS")) {
            String expected = (String) StpUtil.getSession().get(AuthConstants.CSRF_SESSION_KEY);
            String supplied = request.getHeader(AuthConstants.CSRF_HEADER_NAME);
            // 令牌绑定服务器端会话；比较时避免按字符位置提前返回。
            if (expected == null || supplied == null || !MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8),
                    supplied.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(ApiErrorCode.CSRF_INVALID, MessageConstants.CSRF_INVALID);
            }
        }
        return true;
    }
}
