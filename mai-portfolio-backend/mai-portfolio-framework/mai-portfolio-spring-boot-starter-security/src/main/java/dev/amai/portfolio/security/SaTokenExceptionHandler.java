package dev.amai.portfolio.security;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 统一转换 Sa-Token 鉴权异常，业务模块无需重复捕获。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SaTokenExceptionHandler {
    @ExceptionHandler(NotLoginException.class)
    ResponseEntity<R<Void>> handleNotLoggedIn(NotLoginException error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.failure(
            ApiErrorCode.UNAUTHENTICATED.name(), SecurityMessageConstants.LOGIN_REQUIRED, List.of()));
    }

    @ExceptionHandler(NotRoleException.class)
    ResponseEntity<R<Void>> handleMissingRole(NotRoleException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.failure(
            ApiErrorCode.FORBIDDEN.name(), SecurityMessageConstants.PERMISSION_DENIED, List.of()));
    }
}
