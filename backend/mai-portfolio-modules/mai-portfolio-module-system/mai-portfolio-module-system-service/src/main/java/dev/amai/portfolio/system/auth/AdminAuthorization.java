package dev.amai.portfolio.system.auth;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 只负责后台业务的 OWNER 授权，不再承载登录入口、HTTPS 或 CSRF 逻辑。 */
@Component
public class AdminAuthorization implements HandlerInterceptor, WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(AuthConstants.ADMIN_PATH_PATTERN).order(10);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        StpUtil.checkLogin();
        StpUtil.checkRole(AuthConstants.OWNER_ROLE);
        return true;
    }
}
