package dev.amai.portfolio.asset.component;

import dev.amai.portfolio.asset.api.AssetType;
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
import static org.springframework.http.MediaType.IMAGE_JPEG_VALUE;
import static org.springframework.http.MediaType.IMAGE_PNG_VALUE;

/** 在解码前检查尺寸，按用途设限；重编码移除 EXIF 与尾随内容，不负责业务绑定。 */
@Component
public class ImageContentValidator {
    private static final int FIRST_IMAGE_INDEX = 0;
    private final AvatarUploadProperties avatarUploadProperties;

    public ImageContentValidator(AvatarUploadProperties avatarUploadProperties) {
        this.avatarUploadProperties = avatarUploadProperties;
    }

    public int maxBytes(AssetUsage purpose) {
        return purpose == AssetUsage.AVATAR
                ? avatarUploadProperties.maxBytes()
                : purpose.maxBytes();
    }

    private int maxDimension(AssetUsage purpose) {
        return purpose == AssetUsage.AVATAR
                ? avatarUploadProperties.maxDimension()
                : purpose.maxDimension();
    }

    public CheckedImage inspect(byte[] content, String declaredContentType, AssetUsage purpose) {
        // 1. 根据用途及声明 MIME 校验输入容量，不信任文件扩展名。
        if (purpose == null
                || purpose.assetType() != AssetType.IMAGE) {
            throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
        }
        if (content == null || content.length == 0) {
            throw invalid(AssetMessageConstants.IMAGE_EMPTY);
        }
        if (content.length > maxBytes(purpose)) {
            throw invalid(AssetMessageConstants.IMAGE_TOO_LARGE);
        }
        if (!IMAGE_PNG_VALUE.equals(declaredContentType)
                && !IMAGE_JPEG_VALUE.equals(declaredContentType)) {
            throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
        }
        try (ImageInputStream input =
                ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            var imageReaders = ImageIO.getImageReaders(input);
            if (!imageReaders.hasNext()) {
                throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
            }
            ImageReader imageReader = imageReaders.next();
            try {
                // 2. 只检查首张图片；先读取格式和尺寸，确认上限后才分配像素缓冲区。
                imageReader.setInput(input, true, true);
                String format = imageReader.getFormatName().toLowerCase(Locale.ROOT);
                String mime =
                        "png".equals(format)
                                ? IMAGE_PNG_VALUE
                                : "jpeg".equals(format) ? IMAGE_JPEG_VALUE : "";
                if (!mime.equals(declaredContentType)) {
                    throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
                }
                int width = imageReader.getWidth(FIRST_IMAGE_INDEX);
                int height = imageReader.getHeight(FIRST_IMAGE_INDEX);
                if (width < 1
                        || height < 1
                        || width > maxDimension(purpose)
                        || height > maxDimension(purpose)) {
                    throw invalid(AssetMessageConstants.IMAGE_DIMENSIONS_INVALID);
                }
                // 3. 重编码去掉原文件附加内容，再检查输出容量；不把原始字节写入存储。
                BufferedImage pixels = imageReader.read(FIRST_IMAGE_INDEX);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                if (pixels == null || !ImageIO.write(pixels, format, output)) {
                    throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
                }
                byte[] bytes = output.toByteArray();
                // 重编码后也检查上限；不能用小压缩文件绕过存储大小策略。
                if (bytes.length > maxBytes(purpose)) {
                    throw invalid(AssetMessageConstants.IMAGE_TOO_LARGE);
                }
                return new CheckedImage(
                        bytes, mime, IMAGE_PNG_VALUE.equals(mime) ? "png" : "jpg", width, height);
            } finally {
                // 4. 无论校验/重编码是否成功，都释放 ImageReader，外层关闭输入流。
                imageReader.dispose();
            }
        } catch (IOException | IllegalArgumentException invalidImage) {
            throw invalid(AssetMessageConstants.IMAGE_TYPE_INVALID);
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }

    public record CheckedImage(
            byte[] bytes,

            String mime,

            String extension,

            int width,

            int height) {}
}
