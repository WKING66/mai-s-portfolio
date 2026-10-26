package dev.amai.portfolio.web.exception;

/** 业务层显式拒绝，交由统一异常边界按 code 映射 HTTP 状态与响应信封。 */
public class ApiException extends RuntimeException {
    private final ApiErrorCode code;

    public ApiException(ApiErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ApiErrorCode code() {
        return code;
    }
}
