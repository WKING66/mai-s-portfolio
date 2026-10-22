package dev.amai.portfolio.system.auth;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.api.OwnerView;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.service.AccountAuthenticationService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 请求认证入口：HTTPS、Redis 登录身份和 CSRF；管理视图的条件角色授权也在入口完成。 */
@Component
public class AuthenticationInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    /** 先于接口注解角色鉴权，统一完成请求与会话有效性检查。 */
    private static final int AUTHENTICATION_INTERCEPTOR_ORDER = -100;

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private final RequestSecurityPolicy requestSecurityPolicy;
    private final AccountAuthenticationService accountAuthenticationService;

    public AuthenticationInterceptor(
            RequestSecurityPolicy requestSecurityPolicy,
            AccountAuthenticationService accountAuthenticationService) {
        this.requestSecurityPolicy = requestSecurityPolicy;
        this.accountAuthenticationService = accountAuthenticationService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns(AuthConstants.API_PATH_PATTERN)
                .order(AUTHENTICATION_INTERCEPTOR_ORDER);
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {

        // 1. 根据精确认证入口、HTTP 方法及接口注解决定是否进入认证边界。
        String path = request.getRequestURI().substring(request.getContextPath().length());
        boolean sessionEndpoint = AuthConstants.AUTH_SESSION_PATH.equals(path);
        String method = request.getMethod();
        boolean credentialsEndpoint =
                sessionEndpoint && ("GET".equals(method) || "POST".equals(method))
                        || AuthConstants.AUTH_REGISTER_PATH.equals(path) && "POST".equals(method);
        // 角色/页面路径不再决定领域分类；接口注解及写操作决定是否进入认证边界。
        boolean ownerView = isOwnerView(request, handler);
        boolean protectedEndpoint = !SAFE_METHODS.contains(method) || requiresSession(handler) || ownerView;
        if (!credentialsEndpoint && !protectedEndpoint) {
            return true;
        }
        // 2. 先校验传输安全；仅会话查询/登录和注册豁免已有会话及 CSRF。
        // 只信任容器重建的连接状态，不自行信任客户端转发头。
        requestSecurityPolicy.requireSecureTransport(request);
        if (AuthConstants.AUTH_REGISTER_PATH.equals(path) && "POST".equals(method)) {
            // 只豁免精确注册路由的登录/CSRF；HTTPS 已校验，来源与额度由服务层核对。
            return true;
        }
        if (sessionEndpoint && ("GET".equals(method) || "POST".equals(method))) {
            // GET 在服务中读取匿名/Redis 身份状态，POST 通过凭据建立登录态。
            return true;
        }
        // 3. 校验 Redis 登录身份上下文；合法注销仍允许停用账号退出。
        // 停用账号仍允许显式注销；其他入口统一读取 Redis 会话中的身份上下文。
        if (sessionEndpoint && "DELETE".equals(method)) {
            StpUtil.getLoginIdAsLong();
        } else {
            accountAuthenticationService.currentAccount();
        }
        // 混合视图只在管理查询时授权；固定权限接口继续由 @SaCheckRole 统一处理。
        if (ownerView) {
            StpUtil.checkRole(AuthConstants.OWNER_ROLE);
        }
        // 4. 写操作采用恒定时间比较核对 CSRF。
        if (!SAFE_METHODS.contains(method)) {
            String expected = (String) StpUtil.getSession().get(AuthConstants.CSRF_SESSION_KEY);
            String supplied = request.getHeader(AuthConstants.CSRF_HEADER_NAME);
            if (expected == null
                    || supplied == null
                    || expected.isBlank()
                    || supplied.isBlank()
                    || !MessageDigest.isEqual(
                            expected.getBytes(StandardCharsets.UTF_8),
                            supplied.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(
                        ApiErrorCode.CSRF_INVALID, SystemMessageConstants.CSRF_INVALID);
            }
        }
        return true;
    }

    private boolean isOwnerView(HttpServletRequest request, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || !AnnotatedElementUtils.hasAnnotation(method.getMethod(), OwnerView.class)) {
            return false;
        }
        String[] views = request.getParameterValues("view");
        if (views == null) {
            return false;
        }
        // 任一管理值都先要求授权，避免重复参数的绑定差异暴露管理投影。
        return Arrays.asList(views).contains("MANAGE");
    }

    private boolean requiresSession(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return false;
        }
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), SaCheckLogin.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), SaCheckLogin.class)
                || AnnotatedElementUtils.hasAnnotation(method.getMethod(), SaCheckRole.class)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), SaCheckRole.class);
    }
}
