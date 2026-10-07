package dev.amai.portfolio.asset.api;

/** 图片内容处理组件，复用真实格式校验、解码上限和去元数据重编码。 */
public interface ImageUploadComponent {
    int maxBytes(ImageUploadPurpose purpose);
    long upload(byte[] content, String contentType, ImageUploadPurpose purpose);
}
