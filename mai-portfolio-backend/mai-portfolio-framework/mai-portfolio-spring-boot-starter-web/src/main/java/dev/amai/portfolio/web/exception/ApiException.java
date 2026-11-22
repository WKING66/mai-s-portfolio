package dev.amai.portfolio.web.exception;

/** 业务层显式拒绝，交由统一异常边界按 code 映射 HTTP 状态与响应信封。 */
public class ApiException extends RuntimeException {
    private final ApiErrorCode code;
    private final Integer retryAfterSeconds;

    public ApiException(ApiErrorCode code, String message) {
        this(code, message, null);
    }

    public ApiException(ApiErrorCode code, String message, Integer retryAfterSeconds) {
        super(message);
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public ApiErrorCode code() {
        return code;
    }
}
