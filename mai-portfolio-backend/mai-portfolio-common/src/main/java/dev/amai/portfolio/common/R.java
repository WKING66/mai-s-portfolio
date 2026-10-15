package dev.amai.portfolio.common;

import java.util.List;

/** 统一响应信封；data 等字段名称是既有 JSON 契约，HTTP 状态由调用方决定。 */
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

    /** 详情保存为不可变快照；沿用 List.copyOf 对 null 列表或元素的拒绝语义。 */
    public static R<Void> failure(String code, String message, List<String> details) {
        return new R<>(code, message, null, List.copyOf(details));
    }
}
