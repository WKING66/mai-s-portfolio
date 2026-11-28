package dev.amai.portfolio.knowledge.api;

public enum KnowledgeSourceType {
    DOCUMENT(1), PROFILE(2);

    private final int code;

    KnowledgeSourceType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
