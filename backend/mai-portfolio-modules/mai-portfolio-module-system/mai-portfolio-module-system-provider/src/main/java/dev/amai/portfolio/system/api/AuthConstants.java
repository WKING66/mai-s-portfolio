package dev.amai.portfolio.system.api;

public final class AuthConstants {
    public static final String OWNER_ROLE = "OWNER";
    public static final String SESSION_COOKIE_NAME = "portfolio_session";
    public static final String OPENAPI_OWNER_SESSION_SCHEME = "ownerSession";
    public static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";
    public static final String ADMIN_SESSION_PATH = "/api/v1/admin/session";
    public static final String LOGIN_CHALLENGE_PATH = ADMIN_SESSION_PATH + "/challenge";
    public static final String CSRF_SESSION_KEY = "csrf";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";

    private AuthConstants() {
    }
}
