package dev.amai.portfolio.common;

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
