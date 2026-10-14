package dev.amai.portfolio.common;

import java.util.List;

public record R<T>(
    String code,
    String message,
    T data,
    List<String> details
) {
    public static final String SUCCESS_CODE = "OK";

    public static <T> R<T> success(T data) {
        return new R<>(SUCCESS_CODE, ResponseMessageConstants.SUCCESS, data, List.of());
    }

    public static R<Void> failure(String code, String message, List<String> details) {
        return new R<>(code, message, null, List.copyOf(details));
    }
}
