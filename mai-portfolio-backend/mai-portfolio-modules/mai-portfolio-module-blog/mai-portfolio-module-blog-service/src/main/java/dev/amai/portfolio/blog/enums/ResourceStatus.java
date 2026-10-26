package dev.amai.portfolio.blog.enums;

public enum ResourceStatus {
    READY(0),
    FETCH_FAILED(1),
    INVALID(2);
    private final int code;

    ResourceStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ResourceStatus from(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalStateException("Invalid ResourceStatus code");
    }
}
