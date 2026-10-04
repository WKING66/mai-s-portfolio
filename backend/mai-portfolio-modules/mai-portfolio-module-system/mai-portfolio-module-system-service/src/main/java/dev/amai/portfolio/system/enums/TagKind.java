package dev.amai.portfolio.system.enums;

public enum TagKind {
    TOPIC(0), TECH(1);

    private final int code;

    TagKind(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
