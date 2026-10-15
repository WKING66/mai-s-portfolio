package dev.amai.portfolio.blog.enums;

public enum ResourceType {
    IMAGE(0),
    ATTACHMENT(1);
    private final int code;

    ResourceType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ResourceType from(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalStateException("Invalid ResourceType code");
    }
}
