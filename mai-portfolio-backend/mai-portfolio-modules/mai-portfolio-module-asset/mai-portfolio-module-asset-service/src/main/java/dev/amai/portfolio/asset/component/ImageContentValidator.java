package dev.amai.portfolio.asset.component;

import static org.springframework.http.MediaType.IMAGE_PNG_VALUE;
import static org.springframework.http.MediaType.IMAGE_JPEG_VALUE;

import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.config.AvatarUploadProperties;
import dev.amai.portfolio.asset.constant.AssetMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/** 在解码前检查尺寸，按用途设限；重编码移除 EXIF 与尾随内容，不负责业务绑定。 */
@Component
public class ImageContentValidator {
    private final AvatarUploadProperties avatarLimits;

    public ImageContentValidator(AvatarUploadProperties avatarLimits) {
        this.avatarLimits = avatarLimits;
    }

    public int maxBytes(AssetUsage purpose) {
        return purpose == AssetUsage.AVATAR ? avatarLimits.maxBytes() : purpose.maxBytes();
    }

    private int maxDimension(AssetUsage purpose) {
        return purpose == AssetUsage.AVATAR ? avatarLimits.maxDimension() : purpose.maxDimension();
    }

    public CheckedImage inspect(byte[] content, String declaredContentType, AssetUsage purpose) {
        if (purpose == null || purpose.assetType() != dev.amai.portfolio.asset.api.AssetType.IMAGE) {
            throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
        }
        if (content == null || content.length == 0) {
            throw invalid(AssetMessageConstants.IMAGE_EMPTY);
        }
        if (content.length > maxBytes(purpose)) {
            throw invalid(AssetMessageConstants.IMAGE_TOO_LARGE);
        }
        if (!IMAGE_PNG_VALUE.equals(declaredContentType) && !IMAGE_JPEG_VALUE.equals(declaredContentType)) {
            throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String mime = "png".equals(format) ? IMAGE_PNG_VALUE : "jpeg".equals(format) ? IMAGE_JPEG_VALUE : "";
                if (!mime.equals(declaredContentType)) {
                    throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > maxDimension(purpose) || height > maxDimension(purpose)) {
                    throw invalid(AssetMessageConstants.IMAGE_DIMENSIONS_INVALID);
                }
                BufferedImage pixels = reader.read(0);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (pixels == null || !ImageIO.write(pixels, format, output)) {
                    throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
                }
                byte[] bytes = output.toByteArray();
                // 重编码后也检查上限；不能用小压缩文件绕过存储大小策略。
                if (bytes.length > maxBytes(purpose)) {
                    throw invalid(AssetMessageConstants.IMAGE_TOO_LARGE);
                }
                return new CheckedImage(bytes, mime, IMAGE_PNG_VALUE.equals(mime) ? "png" : "jpg", width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException | IllegalArgumentException invalidImage) {
            throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
        }
    }


    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }

    public record CheckedImage(byte[] bytes, String mime, String extension, int width, int height) { }
}
