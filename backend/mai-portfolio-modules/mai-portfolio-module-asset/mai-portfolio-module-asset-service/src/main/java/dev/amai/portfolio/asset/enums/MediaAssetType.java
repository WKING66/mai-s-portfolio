package dev.amai.portfolio.asset.enums;

public enum MediaAssetType {
    IMAGE(0), DOCUMENT(1);

    private final int code;

    MediaAssetType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
