package dev.amai.portfolio.system.auth;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 通用认证边界：HTTPS、登录态、账号状态与 CSRF，不负责站长角色授权。 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final SecurityProperties security;
    private final AuthService auth;

    public AuthenticationInterceptor(SecurityProperties security, AuthService auth) {
        this.security = security;
        this.auth = auth;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
            .addPathPatterns(AuthConstants.AUTH_PATH_PATTERN, AuthConstants.ADMIN_PATH_PATTERN,
                AuthConstants.PROJECT_LIST_PATH)
            .order(-100);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (ProjectViewAccess.isPublicProjectRequest(request)) {
            return true;
        }
        // 只信任容器重建的安全连接状态，不自行信任客户端提交的转发头。
        if (security.requireHttps() && !request.isSecure()) {
            throw new ApiException(ApiErrorCode.HTTPS_REQUIRED, SystemMessageConstants.LOGIN_HTTPS_REQUIRED);
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean sessionEndpoint = AuthConstants.AUTH_SESSION_PATH.equals(path);
        String method = request.getMethod();
        if (AuthConstants.AUTH_REGISTER_PATH.equals(path) && "POST".equals(method)) {
            // 只豁免精确注册路由的登录/CSRF；HTTPS 已校验，来源与额度由服务层核对。
            return true;
        }
        if (sessionEndpoint && ("GET".equals(method) || "POST".equals(method))) {
            // GET 在服务中查询匿名/账号状态，POST 通过凭据建立登录态。
            return true;
        }
        StpUtil.checkLogin();
        // 停用账号不能继续执行业务，但仍允许持有合法 CSRF 的用户主动注销。
        if (!(sessionEndpoint && "DELETE".equals(method))) {
            auth.checkActiveAccount();
        }
        if (!SAFE_METHODS.contains(method)) {
            String expected = (String) StpUtil.getSession().get(AuthConstants.CSRF_SESSION_KEY);
            String supplied = request.getHeader(AuthConstants.CSRF_HEADER_NAME);
            if (expected == null || supplied == null || expected.isBlank() || supplied.isBlank()
                    || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        supplied.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(ApiErrorCode.CSRF_INVALID, SystemMessageConstants.CSRF_INVALID);
            }
        }
        return true;
    }
}
