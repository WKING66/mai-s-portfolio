package dev.amai.portfolio.enums;

public enum AccountStatus {
    DISABLED(0), ENABLED(1);

    private final int code;

    AccountStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
