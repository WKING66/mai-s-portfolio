package dev.amai.portfolio.asset.api;

/** 服务端控制图片用途和资源边界；不得接收客户端自定义对象路径。 */
public enum ImageUploadPurpose {
    AVATAR("account/avatars", "avatar", 2 * 1024 * 1024, 2048),
    PROJECT_COVER("project/covers", "cover", 8 * 1024 * 1024, 4096);

    private final String prefix;
    private final String filename;
    private final int maxBytes;
    private final int maxDimension;

    ImageUploadPurpose(String prefix, String filename, int maxBytes, int maxDimension) {
        this.prefix = prefix;
        this.filename = filename;
        this.maxBytes = maxBytes;
        this.maxDimension = maxDimension;
    }

    public String prefix() { return prefix; }
    public String filename() { return filename; }
    public int maxBytes() { return maxBytes; }
    public int maxDimension() { return maxDimension; }
}
