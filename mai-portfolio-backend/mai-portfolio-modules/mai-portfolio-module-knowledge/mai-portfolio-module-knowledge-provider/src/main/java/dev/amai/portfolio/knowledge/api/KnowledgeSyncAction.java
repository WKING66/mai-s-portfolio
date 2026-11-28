package dev.amai.portfolio.knowledge.api;

public enum KnowledgeSyncAction {
    BUILD(1), WITHDRAW(2), PURGE(3);

    private final int code;

    KnowledgeSyncAction(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
