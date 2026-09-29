package dev.amai.portfolio.web;

/** Web 协议层的通用提示，不包含任何业务域语义。 */
public final class WebMessageConstants {
    public static final String VALIDATION_FAILED = "请求参数不合法";
    public static final String MALFORMED_REQUEST = "请求体格式不正确";
    public static final String RESOURCE_NOT_FOUND = "资源不存在";
    public static final String DATA_CONFLICT = "数据状态已变化，请刷新后重试";
    public static final String SERVICE_UNAVAILABLE = "服务暂时不可用";

    private WebMessageConstants() {
    }
}
