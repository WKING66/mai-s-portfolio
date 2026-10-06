package dev.amai.portfolio.asset.api;

/** 受控头像图片写入边界；不提供任意路径写入或公开访问授权。 */
public interface ImageAssetUploadService {
    /** HTTP 接收方使用同一配置限制输入流读取量，不预分配超大请求文件。 */
    int maxAvatarBytes();

    /** 校验并重编码图片，保存到对象存储与资产表，返回 READY 资产 ID。 */
    long uploadAvatar(byte[] content, String declaredContentType);
}
