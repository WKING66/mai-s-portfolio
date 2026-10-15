package dev.amai.portfolio.blog.enums;

public enum DocumentOrigin {
    EDITOR(0),
    MARKDOWN(1);
    private final int code;

    DocumentOrigin(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DocumentOrigin from(int code) {
        for (var value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalStateException("Invalid DocumentOrigin code");
    }
}
