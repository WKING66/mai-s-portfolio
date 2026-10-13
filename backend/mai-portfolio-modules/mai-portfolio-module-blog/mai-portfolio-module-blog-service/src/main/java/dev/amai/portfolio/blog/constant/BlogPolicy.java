package dev.amai.portfolio.blog.constant;

/** 博客发布与接口投影的稳定规则，不能与 Document 内容版本或 Asset 状态混用。 */
public final class BlogPolicy {
    public static final int DRAFT = 0, PUBLISHED = 1;
    public static final String PUBLIC_VIEW = "PUBLIC", MANAGE_VIEW = "MANAGE";
    /** RequestParam 的默认值使用字符串，分页安全上限由服务层统一校验。 */
    public static final String DEFAULT_PAGE = "1", DEFAULT_PAGE_SIZE = "12";
    public static final int MAX_PAGE_NUMBER = 10_000, MAX_PAGE_SIZE = 50;
    /** blog_post.allow_visitor_download 的数据库编码，不代表站长的管理导出权限。 */
    public static final int VISITOR_DOWNLOAD_DISABLED = 0, VISITOR_DOWNLOAD_ENABLED = 1;
    public static final int MAX_TITLE_LENGTH = 200,
            MAX_SUMMARY_LENGTH = 2000,
            MAX_SLUG_LENGTH = 160;

    private BlogPolicy() {}
}
