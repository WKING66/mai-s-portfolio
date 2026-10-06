package dev.amai.portfolio.asset.config;

import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 头像上传的统一资源边界；限制在解码前检查，防止超大压缩图耗尽内存。 */
@ConfigurationProperties("portfolio.asset.avatar")
public record AvatarUploadProperties(
    @DefaultValue("2097152") int maxBytes,
    @DefaultValue("2048") int maxDimension
) {
    public AvatarUploadProperties {
        // 限制可配置上界，使最多分配 2048 × 2048 的像素缓冲区。
        if (maxBytes < 1 || maxBytes > 2097152 || maxDimension < 1 || maxDimension > 2048) {
            throw new IllegalArgumentException(AssetMessageConstants.AVATAR_CONFIG_INVALID);
        }
    }
}
