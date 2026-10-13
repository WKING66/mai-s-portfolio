package dev.amai.portfolio.portfolio.constant;

/** 项目分页、文本和关联数量的服务端边界。 */
public final class ProjectConstants {
    /** project_media.role：1 为封面；其他图集关系不参与封面替换。 */
    public static final int COVER_ROLE = 1;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int PUBLIC_PAGE_SIZE = 12;
    public static final int MANAGE_PAGE_SIZE = 20;
    public static final int MAX_TEXT_LENGTH = 16000;
    public static final int MAX_ASSOCIATIONS = 50;
    private ProjectConstants() { }
}
