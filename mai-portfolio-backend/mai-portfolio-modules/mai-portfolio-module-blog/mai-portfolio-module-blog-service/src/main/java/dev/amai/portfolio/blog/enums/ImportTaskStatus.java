package dev.amai.portfolio.blog.enums;

/** 导入记录的业务结果：部分资源失败仍可保存草稿，不能因此报告整篇导入失败。 */
public enum ImportTaskStatus {
    PROCESSING(0),
    SUCCEEDED(1),
    SUCCEEDED_WITH_ISSUES(2),
    FAILED(3),
    INTERRUPTED(4);

    private final int code;

    ImportTaskStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 数据库存固定编码，对外仍返回枚举名称；未知编码明确报错，不冒充其他业务状态。 */
    public static ImportTaskStatus fromCode(int code) {
        for (ImportTaskStatus value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown ImportTaskStatus code: " + code);
    }
}
