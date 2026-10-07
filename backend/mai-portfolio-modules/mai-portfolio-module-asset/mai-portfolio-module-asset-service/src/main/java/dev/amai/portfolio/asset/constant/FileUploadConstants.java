package dev.amai.portfolio.asset.constant;

/** 管理上传入口仅支持明确实现的图片用途，禁止任意文件/对象路径上传。 */
public final class FileUploadConstants {
    public static final String IMAGE_PATH = "/api/v1/admin/assets/images";
    private FileUploadConstants() { }
}
