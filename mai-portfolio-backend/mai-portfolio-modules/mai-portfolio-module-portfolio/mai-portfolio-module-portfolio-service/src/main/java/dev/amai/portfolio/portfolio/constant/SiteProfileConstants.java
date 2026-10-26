package dev.amai.portfolio.portfolio.constant;

/** 网站公开资料的字段存储边界，与账号资料及项目内容无关。 */
public final class SiteProfileConstants {
    public static final int MAX_NAME_LENGTH = 100;
    public static final int MAX_HEADLINE_LENGTH = 200;
    public static final int MAX_INTRO_LENGTH = 16000;
    public static final int MAX_GITHUB_URL_LENGTH = 2048;
    public static final int MAX_EMAIL_LENGTH = 254;

    private SiteProfileConstants() {
    }
}
