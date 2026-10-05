package dev.amai.portfolio.system.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 中性列表路径不能绕过管理边界，公开视图也不因 OWNER Cookie 自动扩大。 */
class ProjectViewAuthorizationTest {
    @Test
    void publicDefaultDoesNotRequireLogin() {
        try (var stp = mockStatic(StpUtil.class)) {
            AuthService auth = mock(AuthService.class);
            var request = new MockHttpServletRequest("GET", AuthConstants.PROJECT_LIST_PATH);
            assertThat(new AdminAuthorization().preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
            assertThat(interceptor(auth, true).preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
            stp.verifyNoInteractions();
            verifyNoInteractions(auth);
        }
    }

    @Test
    void manageChecksLoginActiveAccountAndOwner() {
        try (var stp = mockStatic(StpUtil.class)) {
            AuthService auth = mock(AuthService.class);
            var request = new MockHttpServletRequest("GET", AuthConstants.PROJECT_LIST_PATH);
            request.addParameter("view", "MANAGE");
            interceptor(auth, false).preHandle(request, new MockHttpServletResponse(), new Object());
            new AdminAuthorization().preHandle(request, new MockHttpServletResponse(), new Object());
            verify(auth).checkActiveAccount();
            stp.verify(() -> StpUtil.checkRole(AuthConstants.OWNER_ROLE));
        }
    }

    @Test
    void productionManageRequiresActualHttps() {
        AuthService auth = mock(AuthService.class);
        var request = new MockHttpServletRequest("GET", AuthConstants.PROJECT_LIST_PATH);
        request.addParameter("view", "MANAGE");
        request.addHeader("X-Forwarded-Proto", "https");
        assertThatThrownBy(() -> interceptor(auth, true).preHandle(
            request, new MockHttpServletResponse(), new Object())).isInstanceOf(ApiException.class);
    }

    private AuthenticationInterceptor interceptor(AuthService auth, boolean https) {
        return new AuthenticationInterceptor(new SecurityProperties(List.of("https://example.test"),
            new ByteArrayResource(new byte[0]), https, 5, Duration.ofMinutes(5),
            5, Duration.ofMinutes(5)), auth);
    }
}
