package dev.amai.portfolio.system.auth;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
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
        // 请求 URI 先剥离 context-path；MockMvc 与容器采用不同 servlet 映射时也保持一致。
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = contextPath.isEmpty() ? requestUri : requestUri.substring(contextPath.length());
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
                throw new ApiException(ApiErrorCode.CSRF_INVALID, SystemMessageConstants.CSRF_INVALID);
            }
        }
        return true;
    }
}
