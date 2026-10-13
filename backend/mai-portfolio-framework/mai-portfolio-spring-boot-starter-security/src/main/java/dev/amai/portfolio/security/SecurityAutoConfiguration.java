package dev.amai.portfolio.security;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.interceptor.SaInterceptor;
import dev.amai.portfolio.redis.autoconfigure.RedisInfrastructureAutoConfiguration;
import dev.amai.portfolio.redis.define.cache.RedisKeys;
import dev.amai.portfolio.security.password.Argon2PasswordHasher;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.security.session.NamespacedSaTokenDao;
import jakarta.servlet.DispatcherType;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 提供 Sa-Token 注解鉴权与可被业务应用覆盖的密码散列基础设施。 */
@AutoConfiguration(after = RedisInfrastructureAutoConfiguration.class)
public class SecurityAutoConfiguration implements WebMvcConfigurer {
    /**
     * Sa-Token 1.45 的默认 Filter 注册只覆盖 REQUEST。流式响应完成后的 ASYNC
     * 派发仍会经过 MVC 鉴权拦截器，必须在当前容器线程重新绑定请求上下文。
     * 复用官方 Filter 的绑定及 finally 清理，并保持它原有的过滤器顺序。
     */
    @Bean
    FilterRegistrationBean<SaTokenContextFilterForJakartaServlet> saTokenContextDispatchRegistration(
            SaTokenContextFilterForJakartaServlet filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
        registration.setAsyncSupported(true);
        registration.setOrder(OrderUtils.getOrder(filter.getClass(), Ordered.LOWEST_PRECEDENCE));
        return registration;
    }

    /**
     * 启用 {@code @SaCheckLogin}、{@code @SaCheckRole} 等接口级鉴权注解。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");
    }

    @Bean
    @ConditionalOnMissingBean(PasswordHasher.class)
    PasswordHasher passwordHasher() {
        return new Argon2PasswordHasher();
    }

    @Bean
    @ConditionalOnMissingBean(SaTokenExceptionHandler.class)
    SaTokenExceptionHandler saTokenExceptionHandler() {
        return new SaTokenExceptionHandler();
    }

    /**
     * 将 Sa-Token 的 token、会话和角色缓存统一持久化到 Redisson。
     *
     * <p>固定使用 Sa-Token 1.45 的实现，以兼容 Redis 3.2.1。</p>
     */
    @Bean
    @ConditionalOnMissingBean(SaTokenDao.class)
    SaTokenDao saTokenDao(RedissonClient redisson, RedisKeys keys) {
        return new NamespacedSaTokenDao(redisson, keys);
    }
}
