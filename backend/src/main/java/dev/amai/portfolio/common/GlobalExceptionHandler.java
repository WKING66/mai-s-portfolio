package dev.amai.portfolio.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<R<Void>> handleBusiness(ApiException error) {
        return ResponseEntity.status(error.code().status())
            .body(R.failure(error.code().name(), error.getMessage(), List.of()));
    }

    @ExceptionHandler(NotLoginException.class)
    ResponseEntity<R<Void>> handleNotLoggedIn(NotLoginException error) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.failure(
            ApiErrorCode.UNAUTHENTICATED.name(), MessageConstants.LOGIN_REQUIRED, List.of()));
    }

    @ExceptionHandler(NotRoleException.class)
    ResponseEntity<R<Void>> handleMissingRole(NotRoleException error) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.failure(
            ApiErrorCode.FORBIDDEN.name(), MessageConstants.ADMIN_FORBIDDEN, List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException error) {
        List<String> details = error.getBindingResult().getFieldErrors().stream()
            .map(field -> field.getField() + ": " + field.getDefaultMessage())
            .distinct().sorted(Comparator.naturalOrder()).toList();
        return ResponseEntity.unprocessableEntity().body(R.failure(
            ApiErrorCode.VALIDATION_FAILED.name(), MessageConstants.VALIDATION_FAILED, details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<R<Void>> handleMalformedBody(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(R.failure(
            ApiErrorCode.MALFORMED_REQUEST.name(), MessageConstants.MALFORMED_REQUEST, List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<R<Void>> handleUnknownRoute(NoResourceFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.failure(
            ApiErrorCode.NOT_FOUND.name(), MessageConstants.RESOURCE_NOT_FOUND, List.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<R<Void>> handleDataConflict(DataIntegrityViolationException error) {
        // 唯一索引与外键仅是并发/异常情况下的最后防线，不能代替 Service 的业务校验。
        LOG.error("Unexpected database integrity violation", error);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(R.failure(
            ApiErrorCode.DATA_CONFLICT.name(), MessageConstants.DATA_CONFLICT, List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<R<Void>> handleUnexpected(Exception error) {
        // 完整堆栈只写服务端日志；响应不暴露 SQL、路径或第三方服务的内部细节。
        LOG.error("Unexpected API error", error);
        return ResponseEntity.internalServerError().body(R.failure(
            ApiErrorCode.INTERNAL_ERROR.name(), MessageConstants.SERVICE_UNAVAILABLE, List.of()));
    }
}
