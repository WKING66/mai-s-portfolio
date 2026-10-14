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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 显式业务拒绝使用其错误码和 HTTP 状态，不转换为 DTO 校验失败。 */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<R<Void>> handleBusiness(ApiException exception) {
        return ResponseEntity.status(exception.code().status())
                .body(R.failure(exception.code().name(), exception.getMessage(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException exception) {
        // 1. 仅投影现有字段错误，去重并排序，避免响应受校验执行顺序影响。
        List<String> details =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(field -> field.getField() + ": " + field.getDefaultMessage())
                        .distinct()
                        .sorted(Comparator.naturalOrder())
                        .toList();
        // 2. 保留参数校验专属的 422 信封，与业务拒绝的响应边界分离。
        return ResponseEntity.unprocessableEntity()
                .body(
                        R.failure(
                                ApiErrorCode.VALIDATION_FAILED.name(),
                                WebMessageConstants.VALIDATION_FAILED,
                                details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<R<Void>> handleMalformedBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest()
                .body(
                        R.failure(
                                ApiErrorCode.MALFORMED_REQUEST.name(),
                                WebMessageConstants.MALFORMED_REQUEST,
                                List.of()));
    }

    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MissingServletRequestPartException.class
    })
    ResponseEntity<R<Void>> handleMissingRequestField(Exception exception) {
        return ResponseEntity.badRequest()
                .body(
                        R.failure(
                                ApiErrorCode.MALFORMED_REQUEST.name(),
                                WebMessageConstants.MALFORMED_REQUEST,
                                List.of()));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<R<Void>> handlePayloadTooLarge(
            org.springframework.web.multipart.MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(ApiErrorCode.PAYLOAD_TOO_LARGE.status())
                .body(
                        R.failure(
                                ApiErrorCode.PAYLOAD_TOO_LARGE.name(),
                                WebMessageConstants.PAYLOAD_TOO_LARGE,
                                List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<R<Void>> handleUnknownRoute(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                        R.failure(
                                ApiErrorCode.NOT_FOUND.name(),
                                WebMessageConstants.RESOURCE_NOT_FOUND,
                                List.of()));
    }

    /** 参数绑定在 Controller 调用之前发生，不应被未知系统异常兜底误报为 500。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<R<Void>> handleMalformedParameter(
            MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest()
                .body(
                        R.failure(
                                ApiErrorCode.MALFORMED_REQUEST.name(),
                                WebMessageConstants.MALFORMED_PARAMETER,
                                List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<R<Void>> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException exception) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.getSupportedHttpMethods() != null) {
            headers.setAllow(exception.getSupportedHttpMethods());
        }
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .headers(headers)
                .body(
                        R.failure(
                                ApiErrorCode.METHOD_NOT_ALLOWED.name(),
                                WebMessageConstants.METHOD_NOT_ALLOWED,
                                List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<R<Void>> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(exception.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(headers)
                .body(
                        R.failure(
                                ApiErrorCode.UNSUPPORTED_MEDIA_TYPE.name(),
                                WebMessageConstants.UNSUPPORTED_MEDIA_TYPE,
                                List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<R<Void>> handleUnexpected(Exception exception) {
        // 完整堆栈只写服务端日志；响应不暴露 SQL、路径或第三方服务的内部细节。
        LOG.error("Unexpected API error", exception);
        return ResponseEntity.internalServerError()
                .body(
                        R.failure(
                                ApiErrorCode.INTERNAL_ERROR.name(),
                                WebMessageConstants.SERVICE_UNAVAILABLE,
                                List.of()));
    }
}
