package dev.amai.portfolio.common;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "统一接口响应；文件字节响应除外")
public record R<T>(
    @Schema(description = "成功为 OK，失败为稳定业务错误码", example = "OK") String code,
    @Schema(description = "面向调用方的简要说明", example = MessageConstants.SUCCESS) String message,
    @Schema(description = "成功时的业务数据；失败时为 null") T data,
    @Schema(description = "字段或归档问题等安全详情") List<String> details
) {
    public static final String SUCCESS_CODE = "OK";

    public static <T> R<T> success(T data) {
        return new R<>(SUCCESS_CODE, MessageConstants.SUCCESS, data, List.of());
    }

    public static R<Void> failure(String code, String message, List<String> details) {
        return new R<>(code, message, null, List.copyOf(details));
    }
}
