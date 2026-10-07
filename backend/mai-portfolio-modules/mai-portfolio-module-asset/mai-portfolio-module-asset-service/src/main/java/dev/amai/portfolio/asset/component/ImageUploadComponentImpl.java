package dev.amai.portfolio.asset.component;

import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.AssetUploadComponent;
import dev.amai.portfolio.asset.api.AssetUploadRequest;
import dev.amai.portfolio.asset.api.ImageUploadComponent;
import dev.amai.portfolio.asset.api.ImageUploadPurpose;
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
public class ImageUploadComponentImpl implements ImageUploadComponent {
    private static final String PNG_MIME = "image/png";
    private static final String JPEG_MIME = "image/jpeg";
    private final AssetUploadComponent uploads;
    private final AvatarUploadProperties avatarLimits;

    public ImageUploadComponentImpl(AssetUploadComponent uploads, AvatarUploadProperties avatarLimits) {
        this.uploads = uploads;
        this.avatarLimits = avatarLimits;
    }

    @Override
    public int maxBytes(ImageUploadPurpose purpose) {
        return purpose == ImageUploadPurpose.AVATAR ? avatarLimits.maxBytes() : purpose.maxBytes();
    }

    private int maxDimension(ImageUploadPurpose purpose) {
        return purpose == ImageUploadPurpose.AVATAR ? avatarLimits.maxDimension() : purpose.maxDimension();
    }

    @Override
    public long upload(byte[] content, String contentType, ImageUploadPurpose purpose) {
        if (purpose == null) throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
        CheckedImage image = inspect(content, contentType, purpose);
        return uploads.upload(new AssetUploadRequest(AssetType.IMAGE, purpose.prefix(),
            purpose.filename() + "." + image.extension(), image.mime(), image.bytes(), image.width(), image.height()));
    }

    private CheckedImage inspect(byte[] content, String declaredContentType, ImageUploadPurpose purpose) {
        if (content == null || content.length == 0) {
            throw invalid(AssetMessageConstants.IMAGE_EMPTY);
        }
        if (content.length > maxBytes(purpose)) {
            throw invalid(AssetMessageConstants.IMAGE_TOO_LARGE);
        }
        if (!PNG_MIME.equals(declaredContentType) && !JPEG_MIME.equals(declaredContentType)) {
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
                String mime = "png".equals(format) ? PNG_MIME : "jpeg".equals(format) ? JPEG_MIME : "";
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
                return new CheckedImage(bytes, mime, PNG_MIME.equals(mime) ? "png" : "jpg", width, height);
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

    private record CheckedImage(byte[] bytes, String mime, String extension, int width, int height) { }
}
