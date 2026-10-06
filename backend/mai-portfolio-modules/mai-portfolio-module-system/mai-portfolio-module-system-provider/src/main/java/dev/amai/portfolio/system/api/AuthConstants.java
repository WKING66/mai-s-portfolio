package dev.amai.portfolio.system.api;

public final class AuthConstants {
    public static final String OWNER_ROLE = "OWNER";
    public static final String SESSION_COOKIE_NAME = "portfolio_session";
    public static final String OPENAPI_SESSION_SCHEME = "userSession";
    public static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";
    public static final String AUTH_PATH_PATTERN = "/api/v1/auth/**";
    public static final String AUTH_SESSION_PATH = "/api/v1/auth/session";
    public static final String AUTH_REGISTER_PATH = "/api/v1/auth/register";
    public static final String ACCOUNT_PATH = "/api/v1/account";
    public static final String ACCOUNT_PATH_PATTERN = ACCOUNT_PATH + "/**";
    public static final String PROJECT_LIST_PATH = "/api/v1/projects";
    public static final String PROJECT_VIEW_PARAMETER = "view";
    public static final String PROJECT_MANAGE_VIEW = "MANAGE";
    public static final String CSRF_SESSION_KEY = "csrf";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";

    private AuthConstants() {
    }
}
