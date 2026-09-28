package dev.amai.portfolio.common;

public final class MessageConstants {
    public static final String SUCCESS = "成功";
    public static final String VALIDATION_FAILED = "请求参数不合法";
    public static final String MALFORMED_REQUEST = "请求体格式不正确";
    public static final String RESOURCE_NOT_FOUND = "资源不存在";
    public static final String DATA_CONFLICT = "数据状态已变化，请刷新后重试";
    public static final String SERVICE_UNAVAILABLE = "服务暂时不可用";
    public static final String ORIGIN_INVALID = "请求来源不允许";
    public static final String BAD_CREDENTIALS = "用户名或密码错误";
    public static final String LOGIN_CHALLENGE_INVALID = "登录加密凭证已失效，请重新获取";
    public static final String LOGIN_CIPHERTEXT_INVALID = "加密密码无效，请重新获取登录凭证";
    public static final String LOGIN_CHALLENGE_BUSY = "登录请求过多，请稍后重试";
    public static final String LOGIN_CRYPTO_UNAVAILABLE = "登录加密服务暂不可用";
    public static final String LOGIN_USERNAME_REQUIRED = "用户名不能为空";
    public static final String LOGIN_USERNAME_TOO_LONG = "用户名不能超过 64 个字符";
    public static final String LOGIN_CHALLENGE_REQUIRED = "请先获取登录加密凭证";
    public static final String LOGIN_CIPHERTEXT_REQUIRED = "加密密码不能为空";
    public static final String ADMIN_FORBIDDEN = "账号没有管理权限";
    public static final String LOGIN_REQUIRED = "请先登录";
    public static final String CSRF_INVALID = "缺少有效的写入令牌";
    public static final String PROFILE_NOT_INITIALIZED = "站点公开资料未正确初始化";
    public static final String PROFILE_NAME_REQUIRED = "昵称不能为空";
    public static final String PROFILE_HEADLINE_REQUIRED = "职业定位不能为空";
    public static final String PROFILE_INTRO_REQUIRED = "个人介绍不能为空";
    public static final String PROFILE_NAME_TOO_LONG = "昵称不能超过 100 个字符";
    public static final String PROFILE_HEADLINE_TOO_LONG = "职业定位不能超过 200 个字符";
    public static final String PROFILE_INTRO_TOO_LONG = "个人介绍不能超过 16000 个字符";
    public static final String PROFILE_GITHUB_TOO_LONG = "GitHub 地址不能超过 2048 个字符";
    public static final String PROFILE_GITHUB_INVALID = "GitHub 地址必须是有效的 github.com 个人主页链接";
    public static final String PROFILE_EMAIL_TOO_LONG = "邮箱不能超过 254 个字符";
    public static final String PROFILE_EMAIL_INVALID = "邮箱格式不正确";
    public static final String PROFILE_UPDATED_AT_REQUIRED = "请先读取最新资料后再提交";
    public static final String TECH_GROUP_UNKNOWN = "未知技术分类编码: ";
    public static final String OWNER_PASSWORD_TOO_SHORT =
        "OWNER_PASSWORD 至少需要 12 个字符；请只在本地环境文件中设置";

    private MessageConstants() {
    }
}
