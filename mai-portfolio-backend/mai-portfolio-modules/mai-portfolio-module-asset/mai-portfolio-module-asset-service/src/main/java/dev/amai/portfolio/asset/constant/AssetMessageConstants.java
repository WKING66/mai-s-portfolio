package dev.amai.portfolio.asset.constant;

/** 媒体写入错误只暴露安全摘要，不回传 OSS 路径、凭据或原始异常。 */
public final class AssetMessageConstants {
    public static final String FILE_CONTENT_NOT_FOUND = "资源不存在或尚未就绪";
    public static final String FILE_READ_FAILED = "资源读取失败，请重试";
    public static final String FILE_METADATA_INVALID = "文件为空、过大或元数据无效";
    public static final String ASSET_NOT_FOUND = "资产不存在或尚未就绪";
    public static final String FILE_EMPTY = "请选择文件";
    public static final String FILE_TOO_LARGE = "文件超过当前用途的大小限制";
    public static final String IMAGE_EMPTY = "请选择图片文件";
    public static final String IMAGE_TOO_LARGE = "图片超过当前用途的大小限制";
    public static final String IMAGE_TYPE_INVALID = "图片内容损坏或无法解码，请重新导出 PNG、JPEG 或 WebP 文件";
    public static final String IMAGE_TYPE_UNSUPPORTED = "图片格式不支持，仅支持 PNG、JPEG 或 WebP";
    public static final String IMAGE_MIME_MISMATCH = "图片实际格式与声明的 MIME 类型不一致，请检查文件内容和上传类型";
    public static final String IMAGE_DIMENSIONS_INVALID = "图片宽高超过当前用途的像素限制";
    public static final String UPLOAD_INVALID = "上传文件或资产元数据不合法";
    public static final String RESOURCE_ACCESS_FAILED = "资源地址暂时无法生成，请稍后重试";
    public static final String UPLOAD_FAILED = "文件上传失败，请稍后重试";
    public static final String AVATAR_CONFIG_INVALID =
            "Avatar limits must be within 2 MiB / 2048 pixels";

    private AssetMessageConstants() {}
}
