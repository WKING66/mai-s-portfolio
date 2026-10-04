package dev.amai.portfolio.system.constant;

public final class SystemMessageConstants {
    public static final String ORIGIN_INVALID = "请求来源不允许";
    public static final String BAD_CREDENTIALS = "用户名或密码错误";
    public static final String LOGIN_CIPHERTEXT_INVALID = "登录加密凭据格式无效";
    public static final String LOGIN_RATE_LIMITED = "登录尝试过于频繁，请稍后重试";
    public static final String LOGIN_HTTPS_REQUIRED = "认证接口必须通过 HTTPS 访问";
    public static final String LOGIN_PRIVATE_KEY_INVALID = "必须配置有效的外部 PKCS#8 RSA-2048 私钥文件";
    public static final String LOGIN_USERNAME_REQUIRED = "用户名不能为空";
    public static final String LOGIN_USERNAME_TOO_LONG = "用户名不能超过 64 个字符";
    public static final String LOGIN_CIPHERTEXT_REQUIRED = "加密密码不能为空";
    public static final String ACCOUNT_UNAVAILABLE = "账号不存在或已停用";
    public static final String CSRF_INVALID = "缺少有效的写入令牌";
    public static final String OWNER_PASSWORD_TOO_SHORT =
        "OWNER_PASSWORD 至少需要 12 个字符；请只在本地环境文件中设置";
    private SystemMessageConstants() {
    }
}
