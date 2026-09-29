package dev.amai.portfolio.portfolio.constant;

/** 作品集域的校验与业务提示。 */
public final class PortfolioMessageConstants {
    public static final String PROFILE_NOT_INITIALIZED = "站点公开资料未正确初始化";
    public static final String PROFILE_MEDIA_NOT_FOUND = "公开媒体不存在";
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

    private PortfolioMessageConstants() {
    }
}
