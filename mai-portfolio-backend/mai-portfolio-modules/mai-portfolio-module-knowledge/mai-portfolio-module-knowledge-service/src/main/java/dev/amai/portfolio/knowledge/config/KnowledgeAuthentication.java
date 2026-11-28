package dev.amai.portfolio.knowledge.config;

import dev.amai.portfolio.knowledge.controller.KnowledgeController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class KnowledgeAuthentication implements WebMvcConfigurer, HandlerInterceptor {
    private static final String BEARER = "Bearer ";
    private final byte[] token;

    public KnowledgeAuthentication(@Value("${site-guide.ai-server-token:}") String token) {
        this.token = token.isBlank() ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(KnowledgeController.PREFIX, KnowledgeController.PREFIX + "/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (token.length != 0 && header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                && MessageDigest.isEqual(token, header.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType("application/json");
        response.getWriter().write("""
                {"code":"UNAUTHORIZED","message":"Knowledge token is missing or invalid","retryable":false}""");
        return false;
    }
}
