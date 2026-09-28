package dev.amai.portfolio.enums;

public enum FeaturedStatus {
    STANDARD(0), FEATURED(1);

    private final int code;

    FeaturedStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
