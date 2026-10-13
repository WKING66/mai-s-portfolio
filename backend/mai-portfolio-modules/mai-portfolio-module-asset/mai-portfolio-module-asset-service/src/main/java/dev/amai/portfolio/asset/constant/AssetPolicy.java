package dev.amai.portfolio.asset.constant;

/** 上传元数据容量对齐 media_asset 表；文件字节和像素限制仍由 AssetUsage / 头像配置决定。 */
public final class AssetPolicy {
    public static final int MAX_FILENAME_LENGTH = 255;
    public static final int MAX_CONTENT_TYPE_LENGTH = 100;

    private AssetPolicy() {}
}
