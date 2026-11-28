package dev.amai.portfolio.knowledge.api;

public enum KnowledgeSyncStatus {
    PENDING(0), LEASED(1), RETRY(2), COMPLETED(3), TERMINAL(4);

    private final int code;

    KnowledgeSyncStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
