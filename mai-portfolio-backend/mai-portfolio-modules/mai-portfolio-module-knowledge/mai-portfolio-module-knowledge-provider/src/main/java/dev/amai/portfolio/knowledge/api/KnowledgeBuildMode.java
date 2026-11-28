package dev.amai.portfolio.knowledge.api;

public enum KnowledgeBuildMode {
    IMMEDIATE(1), OFF_PEAK(2);

    private final int code;

    KnowledgeBuildMode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
