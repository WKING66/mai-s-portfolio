package dev.amai.portfolio.blog.enums;

/** 同步导入追踪状态，不隐含后台执行器或自动重试。 */
public enum ImportTaskStatus {
    PROCESSING,
    SUCCEEDED,
    SUCCEEDED_WITH_ISSUES,
    FAILED,
    INTERRUPTED
}
