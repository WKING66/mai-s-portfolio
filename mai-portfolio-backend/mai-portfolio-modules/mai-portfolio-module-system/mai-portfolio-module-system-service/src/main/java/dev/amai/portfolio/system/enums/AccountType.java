package dev.amai.portfolio.system.enums;

public enum AccountType {
    OWNER(0), NORMAL(1);

    private final int code;

    AccountType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
