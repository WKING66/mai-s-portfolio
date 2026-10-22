package dev.amai.portfolio.security;

/** 鉴权基础设施可安全返回给客户端的通用提示。 */
public final class SecurityMessageConstants {
    public static final String LOGIN_REQUIRED = "请先登录";
    public static final String PERMISSION_DENIED = "权限不足";

    public static final String SESSION_EXPIRED = "登录凭据已失效，请重新登录";

    private SecurityMessageConstants() {}
}
