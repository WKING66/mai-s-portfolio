package dev.amai.portfolio.blog.enums;

/** 草稿与正式正文的内容阶段；上架/下架另由博客公开状态决定。 */
public enum ContentStage {
    DRAFT(0),
    PUBLISHED(1);

    private final int code;

    ContentStage(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 数据库存固定编码，对外仍返回枚举名称；未知编码明确报错，不冒充其他业务状态。 */
    public static ContentStage fromCode(int code) {
        for (ContentStage value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown ContentStage code: " + code);
    }
}
