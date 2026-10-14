package dev.amai.portfolio.asset.enums;

public enum MediaAssetType {
    IMAGE(0),
    DOCUMENT(1),
    FILE(2);

    private final int code;

    MediaAssetType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static dev.amai.portfolio.asset.api.AssetType toApiType(int code) {
        for (var type : values()) {
            if (type.code == code) {
                return dev.amai.portfolio.asset.api.AssetType.valueOf(type.name());
            }
        }
        throw new IllegalArgumentException("Unknown media asset type");
    }

    public static MediaAssetType from(dev.amai.portfolio.asset.api.AssetType type) {
        return valueOf(type.name());
    }
}
