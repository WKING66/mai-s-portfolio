package dev.amai.portfolio.system.api;

public final class AuthConstants {
    public static final String OWNER_ROLE = "OWNER";
    public static final String SESSION_COOKIE_NAME = "portfolio_session";
    public static final String OPENAPI_SESSION_SCHEME = "userSession";
    public static final String API_PATH_PATTERN = "/api/v1/**";
    public static final String AUTH_PATH = "/api/v1/auth";
    public static final String AUTH_SESSION_ROUTE = "/session";
    public static final String AUTH_REGISTER_ROUTE = "/register";
    // Controller 使用领域前缀与相对路由，鉴权使用由同一事实组成的完整路径。
    public static final String AUTH_SESSION_PATH = AUTH_PATH + AUTH_SESSION_ROUTE;
    public static final String AUTH_REGISTER_PATH = AUTH_PATH + AUTH_REGISTER_ROUTE;
    public static final String ACCOUNT_PATH = "/api/v1/account";
    public static final String CSRF_SESSION_KEY = "csrf";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";

    private AuthConstants() {}
}
