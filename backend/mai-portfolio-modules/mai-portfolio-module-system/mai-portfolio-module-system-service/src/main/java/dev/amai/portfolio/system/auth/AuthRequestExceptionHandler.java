package dev.amai.portfolio.system.auth;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.exception.InvalidCredentialPayloadException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 翻译认证服务抛出的参数异常，不接管其他业务的 MVC 校验。 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthRequestExceptionHandler {
    @ExceptionHandler(InvalidCredentialPayloadException.class)
    public ResponseEntity<R<Void>> invalidCredentialPayload(InvalidCredentialPayloadException error) {
        return ResponseEntity.badRequest().body(R.failure(
            error.code().name(), error.getMessage(), error.details()));
    }
}
