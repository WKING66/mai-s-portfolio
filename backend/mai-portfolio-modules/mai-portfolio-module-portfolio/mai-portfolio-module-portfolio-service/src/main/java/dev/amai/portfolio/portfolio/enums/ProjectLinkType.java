package dev.amai.portfolio.portfolio.enums;

/** API 使用可读名称，数据库使用 SMALLINT 数值码。 */
public enum ProjectLinkType {
    CODE(0), DEMO(1), DOCUMENTATION(2), OTHER(3);
    private final int code;
    ProjectLinkType(int code) { this.code = code; }
    public int code() { return code; }
    public static ProjectLinkType fromCode(int code) {
        for (var value : values()) { if (value.code == code) return value; }
        throw new IllegalArgumentException("Unknown ProjectLinkType code");
    }
}
