package dev.amai.portfolio.asset.api;

/** 用途表达真实校验差异；封面、博客插图、项目截图共用 IMAGE。 */
public enum AssetUsage {
    AVATAR(0, AssetType.IMAGE, "assets/avatars", 2 * 1024 * 1024, 2048),
    IMAGE(1, AssetType.IMAGE, "assets/images", 8 * 1024 * 1024, 4096),
    ATTACHMENT(2, AssetType.FILE, "assets/attachments", 64 * 1024 * 1024, 0);

    private final int code;
    private final AssetType assetType;
    private final String objectPrefix;
    private final int maxBytes;
    private final int maxDimension;

    AssetUsage(int code, AssetType assetType, String objectPrefix, int maxBytes, int maxDimension) {
        this.code = code;
        this.assetType = assetType;
        this.objectPrefix = objectPrefix;
        this.maxBytes = maxBytes;
        this.maxDimension = maxDimension;
    }

    public int code() {
        return code;
    }

    public static AssetUsage fromCode(int code) {
        for (AssetUsage usage : values()) {
            if (usage.code == code) {
                return usage;
            }
        }
        throw new IllegalArgumentException("Unknown asset usage code: " + code);
    }

    public AssetType assetType() {
        return assetType;
    }

    public String objectPrefix() {
        return objectPrefix;
    }

    public int maxBytes() {
        return maxBytes;
    }

    public int maxDimension() {
        return maxDimension;
    }
}
