package dev.amai.portfolio.web.advice;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.web.WebMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<R<Void>> handleBusiness(ApiException error) {
        return ResponseEntity.status(error.code().status())
            .body(R.failure(error.code().name(), error.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException error) {
        List<String> details = error.getBindingResult().getFieldErrors().stream()
            .map(field -> field.getField() + ": " + field.getDefaultMessage())
            .distinct().sorted(Comparator.naturalOrder()).toList();
        return ResponseEntity.unprocessableEntity().body(R.failure(
            ApiErrorCode.VALIDATION_FAILED.name(), WebMessageConstants.VALIDATION_FAILED, details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<R<Void>> handleMalformedBody(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest().body(R.failure(
            ApiErrorCode.MALFORMED_REQUEST.name(), WebMessageConstants.MALFORMED_REQUEST, List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<R<Void>> handleUnknownRoute(NoResourceFoundException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.failure(
            ApiErrorCode.NOT_FOUND.name(), WebMessageConstants.RESOURCE_NOT_FOUND, List.of()));
    }

    /** 参数绑定在 Controller 调用之前发生，不应被未知系统异常兜底误报为 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<R<Void>> handleMalformedParameter(MethodArgumentTypeMismatchException error) {
        return ResponseEntity.badRequest().body(R.failure(
            ApiErrorCode.MALFORMED_REQUEST.name(), WebMessageConstants.MALFORMED_PARAMETER, List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<R<Void>> handleUnsupportedMethod(HttpRequestMethodNotSupportedException error) {
        HttpHeaders headers = new HttpHeaders();
        if (error.getSupportedHttpMethods() != null) {
            headers.setAllow(error.getSupportedHttpMethods());
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers).body(R.failure(
            ApiErrorCode.METHOD_NOT_ALLOWED.name(), WebMessageConstants.METHOD_NOT_ALLOWED, List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<R<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException error) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(error.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).headers(headers).body(R.failure(
            ApiErrorCode.UNSUPPORTED_MEDIA_TYPE.name(), WebMessageConstants.UNSUPPORTED_MEDIA_TYPE, List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<R<Void>> handleUnexpected(Exception error) {
        // 完整堆栈只写服务端日志；响应不暴露 SQL、路径或第三方服务的内部细节。
        LOG.error("Unexpected API error", error);
        return ResponseEntity.internalServerError().body(R.failure(
            ApiErrorCode.INTERNAL_ERROR.name(), WebMessageConstants.SERVICE_UNAVAILABLE, List.of()));
    }
}
