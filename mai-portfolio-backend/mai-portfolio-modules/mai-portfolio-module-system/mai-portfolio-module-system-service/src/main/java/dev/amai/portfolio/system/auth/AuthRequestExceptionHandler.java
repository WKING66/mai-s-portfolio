package dev.amai.portfolio.system.auth;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.controller.AuthController;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 登录和注册的 DTO 约束错误保持原认证参数 400 契约，不改变其他接口的 422 校验。 */
@RestControllerAdvice(assignableTypes = AuthController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthRequestExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> invalidCredentialPayload(MethodArgumentNotValidException error) {
        var details = error.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .distinct()
                .sorted()
                .toList();
        return ResponseEntity.badRequest().body(R.failure(ApiErrorCode.AUTH_INVALID_CREDENTIAL_PAYLOAD.name(),
                SystemMessageConstants.AUTH_REQUEST_INVALID, details));
    }
}
