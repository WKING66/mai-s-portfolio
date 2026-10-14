package dev.amai.portfolio.system.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.auth.RequestSecurityPolicy;
import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.service.AccountAuthenticationService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mock.web.MockHttpServletRequest;

class OwnerAuthorizationServiceImplTest {
    private final AccountAuthenticationService authentication = mock(AccountAuthenticationService.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private OwnerAuthorizationServiceImpl service(boolean https) {
        return new OwnerAuthorizationServiceImpl(authentication, new RequestSecurityPolicy(
            new SecurityProperties(List.of("https://example.test"), new ByteArrayResource(new byte[0]),
                https, 5, Duration.ofMinutes(5), 5, Duration.ofMinutes(5))), request);
    }
    @Test void ownerAccessChecksCurrentCredentialsAndRole() {
        try (var stp = mockStatic(StpUtil.class)) {
            service(false).requireOwner();
            verify(authentication).currentAccount();
            stp.verify(() -> StpUtil.checkRole(AuthConstants.OWNER_ROLE));
        }
    }
    @Test void productionHttpsCannotBeForgedUsingAHeader() {
        request.addHeader("X-Forwarded-Proto", "https");
        assertThatThrownBy(() -> service(true).requireOwner()).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo(ApiErrorCode.HTTPS_REQUIRED));
        verifyNoInteractions(authentication);
    }
    @Test void staleCredentialsCannotReachRoleCheck() {
        when(authentication.currentAccount()).thenThrow(new ApiException(ApiErrorCode.UNAUTHENTICATED, "expired"));
        try (var stp = mockStatic(StpUtil.class)) {
            assertThatThrownBy(() -> service(false).requireOwner()).isInstanceOf(ApiException.class);
            stp.verifyNoInteractions();
        }
    }
}
