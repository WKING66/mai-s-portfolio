package dev.amai.portfolio.document.enums;

public enum DocumentPublishStatus {
    UNPUBLISHED(0), PUBLISHED(1), OFFLINE(2);

    private final int code;

    DocumentPublishStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
