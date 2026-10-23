package dev.amai.portfolio.system.api;

public enum TagKind {
    TOPIC(0),
    TECH(1);

    private final int code;

    TagKind(int code) {
        this.code = code;
    }

    public static TagKind fromCode(int code) {
        for (TagKind kind : values()) {
            if (kind.code == code) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown tag kind code: " + code);
    }

    public int code() {
        return code;
    }
}
