package dev.amai.portfolio.blog.enums;

public enum ResourceSource {
    EMBEDDED(0),
    LOCAL(1),
    REMOTE(2);
    private final int code;

    ResourceSource(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ResourceSource from(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalStateException("Invalid ResourceSource code");
    }
}
