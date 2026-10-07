package dev.amai.portfolio.asset.service.impl;

import dev.amai.portfolio.asset.api.ImageAssetUploadService;
import dev.amai.portfolio.asset.api.ImageUploadComponent;
import dev.amai.portfolio.asset.api.ImageUploadPurpose;
import org.springframework.stereotype.Service;

/** 保留既有头像业务入口；图片与 OSS 资产处理由独立可复用组件完成。 */
@Service
public class ImageAssetUploadServiceImpl implements ImageAssetUploadService {
    private final ImageUploadComponent images;

    public ImageAssetUploadServiceImpl(ImageUploadComponent images) { this.images = images; }

    @Override
    public int maxAvatarBytes() { return images.maxBytes(ImageUploadPurpose.AVATAR); }

    @Override
    public long uploadAvatar(byte[] content, String declaredContentType) {
        return images.upload(content, declaredContentType, ImageUploadPurpose.AVATAR);
    }
}
