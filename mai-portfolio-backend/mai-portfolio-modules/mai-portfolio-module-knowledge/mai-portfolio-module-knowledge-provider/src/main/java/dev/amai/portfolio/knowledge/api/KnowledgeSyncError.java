package dev.amai.portfolio.knowledge.api;

public enum KnowledgeSyncError {
    TRANSPORT(1), VENDOR(2), PROJECTION(3), LEASE_TIMEOUT(4);

    private final int code;

    KnowledgeSyncError(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
