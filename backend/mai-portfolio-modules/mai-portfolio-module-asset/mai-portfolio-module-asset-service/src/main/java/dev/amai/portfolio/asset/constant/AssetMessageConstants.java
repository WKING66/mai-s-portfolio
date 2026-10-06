package dev.amai.portfolio.asset.constant;

/** 媒体写入错误只暴露安全摘要，不回传 OSS 路径、凭据或原始异常。 */
public final class AssetMessageConstants {
    public static final String AVATAR_EMPTY = "请选择头像图片";
    public static final String AVATAR_TOO_LARGE = "头像图片不能超过 2 MiB";
    public static final String AVATAR_TYPE_INVALID = "头像仅支持有效的 PNG 或 JPEG 图片";
    public static final String AVATAR_DIMENSIONS_INVALID = "头像宽高不能超过 2048 像素";
    public static final String AVATAR_UPLOAD_FAILED = "头像上传失败，请稍后重试";
    public static final String AVATAR_CONFIG_INVALID = "Avatar limits must be within 2 MiB / 2048 pixels";

    private AssetMessageConstants() {
    }
}
