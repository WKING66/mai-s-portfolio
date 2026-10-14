package dev.amai.portfolio.asset.enums;

public enum MediaStatus {
    PENDING(0), READY(1), FAILED(2);

    private final int code;

    MediaStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
