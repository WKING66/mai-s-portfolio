package dev.amai.portfolio.portfolio.enums;

/** API 使用可读名称，数据库使用 SMALLINT 数值码。 */
public enum ProjectStatus {
    DRAFT(0), PUBLISHED(1);
    private final int code;
    ProjectStatus(int code) { this.code = code; }
    public int code() { return code; }
    public static ProjectStatus fromCode(int code) {
        for (var value : values()) { if (value.code == code) return value; }
        throw new IllegalArgumentException("Unknown ProjectStatus code");
    }
}
