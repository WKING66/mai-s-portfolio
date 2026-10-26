package dev.amai.portfolio.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import dev.amai.portfolio.common.R;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class SaTokenExceptionHandlerTest {
    private final SaTokenExceptionHandler saTokenExceptionHandler = new SaTokenExceptionHandler();

    @Test
    void returnsUnauthenticatedWithoutLeakingExceptionDetails() {
        ResponseEntity<R<Void>> response =
                saTokenExceptionHandler.handleNotLoggedIn(
                        new NotLoginException(
                                "private-exception-detail",
                                NotLoginException.INVALID_TOKEN,
                                "login"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody())
                .isEqualTo(R.failure("UNAUTHENTICATED", "请先登录", java.util.List.of()));
    }

    @Test
    void returnsForbiddenWithoutLeakingRequiredRole() {
        ResponseEntity<R<Void>> response =
                saTokenExceptionHandler.handleMissingRole(new NotRoleException("private-role"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .isEqualTo(R.failure("FORBIDDEN", "权限不足", java.util.List.of()));
    }
}
