package dev.amai.portfolio.document.enums;

public enum DocumentVisibilityLevel {
    L1(1), L2(2), L3(3);

    private final int code;

    DocumentVisibilityLevel(int code) {
        this.code = code;
    }

    public static DocumentVisibilityLevel fromCode(int code) {
        return switch (code) {
            case 1 -> L1;
            case 2 -> L2;
            case 3 -> L3;
            default -> throw new IllegalArgumentException("Unknown document visibility level: " + code);
        };
    }

    public int code() {
        return code;
    }
}
