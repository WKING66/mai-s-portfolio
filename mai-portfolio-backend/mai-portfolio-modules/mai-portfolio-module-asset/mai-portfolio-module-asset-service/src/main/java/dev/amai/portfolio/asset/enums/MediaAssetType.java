package dev.amai.portfolio.asset.enums;

import dev.amai.portfolio.asset.api.AssetType;

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

    public static AssetType toApiType(int code) {
        for (var type : values()) {
            if (type.code == code) {
                return AssetType.valueOf(type.name());
            }
        }
        throw new IllegalArgumentException("Unknown media asset type");
    }

    public static MediaAssetType from(AssetType type) {
        return valueOf(type.name());
    }
}
