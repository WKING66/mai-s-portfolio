package dev.amai.portfolio.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticationInterceptorTest {
    private final AuthService auth = mock(AuthService.class);

    private AuthenticationInterceptor interceptor(boolean https) {
        return new AuthenticationInterceptor(new SecurityProperties(List.of("https://example.test"),
            new ByteArrayResource(new byte[0]), https, 5, Duration.ofMinutes(10)), auth);
    }

    @Test
    void permitsLoginWhenApplicationUsesContextPath() {
        var request = new MockHttpServletRequest("POST", "/portfolio/api/v1/auth/session");
        request.setContextPath("/portfolio");
        assertThat(interceptor(false).preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        verifyNoInteractions(auth);
    }

    @Test
    void productionRejectsHttpEvenWithForgedForwardedProto() {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/session");
        request.addHeader("X-Forwarded-Proto", "https");
        assertThatThrownBy(() -> interceptor(true).preHandle(request, new MockHttpServletResponse(), new Object()))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.HTTPS_REQUIRED));
        request.setSecure(true);
        assertThat(interceptor(true).preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void businessRequestChecksActiveAccountAndCsrf() {
        try (var stp = mockStatic(StpUtil.class)) {
            SaSession session = new SaSession("test-session");
            session.set(AuthConstants.CSRF_SESSION_KEY, "test-csrf");
            stp.when(StpUtil::getSession).thenReturn(session);
            var request = new MockHttpServletRequest("PATCH", "/api/v1/admin/profile");
            assertThatThrownBy(() -> interceptor(false).preHandle(
                request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.code()).isEqualTo(ApiErrorCode.CSRF_INVALID));
            verify(auth).checkActiveAccount();
            request.addHeader(AuthConstants.CSRF_HEADER_NAME, "test-csrf");
            assertThat(interceptor(false).preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        }
    }

    @Test
    void disabledAccountCanStillLogOutWithValidCsrf() {
        try (var stp = mockStatic(StpUtil.class)) {
            SaSession session = new SaSession("logout-session");
            session.set(AuthConstants.CSRF_SESSION_KEY, "test-csrf");
            stp.when(StpUtil::getSession).thenReturn(session);
            var request = new MockHttpServletRequest("DELETE", "/api/v1/auth/session");
            request.addHeader(AuthConstants.CSRF_HEADER_NAME, "test-csrf");
            assertThat(interceptor(false).preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
            stp.verify(StpUtil::checkLogin);
            verifyNoInteractions(auth);
        }
    }
}
