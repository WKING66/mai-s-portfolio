package dev.amai.portfolio.blog.enums;

/** 显式业务动作；发布草稿与恢复已下架正式正文不可混用。 */
public enum BlogAction {
    PUBLISH,
    UNPUBLISH,
    RESTORE,
    DISCARD_DRAFT
}
