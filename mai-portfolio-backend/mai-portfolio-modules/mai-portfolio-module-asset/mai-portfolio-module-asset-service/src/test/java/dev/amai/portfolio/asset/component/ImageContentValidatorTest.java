package dev.amai.portfolio.asset.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.config.AvatarUploadProperties;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImageContentValidatorTest {
    // 浏览器 Canvas 生成的一像素透明 WebP，不依赖本机验收目录或私人图片。
    private static final byte[] WEBP = Base64.getDecoder().decode(
            "UklGRkAAAABXRUJQVlA4WAoAAAAQAAAAAAAAAAAAQUxQSAIAAAAAAFZQOCAYAAAAMAEAnQEqAQABAAFAJiWkAANwAP79NmgA");
    private final ImageContentValidator validator = new ImageContentValidator(
            new AvatarUploadProperties(2 * 1024 * 1024, 2048));

    @Test
    void webpIsDecodedValidatedAndReencodedAsPng() throws Exception {
        var checked = validator.inspect(WEBP, "image/webp", AssetUsage.IMAGE);
        assertThat(checked.mime()).isEqualTo("image/png");
        assertThat(checked.extension()).isEqualTo("png");
        assertThat(checked.width()).isEqualTo(1);
        assertThat(checked.height()).isEqualTo(1);
        var decoded = ImageIO.read(new ByteArrayInputStream(checked.bytes()));
        assertThat(decoded.getColorModel().hasAlpha()).isTrue();
        assertThat(decoded.getRGB(0, 0) >>> 24).isZero();
    }

    @Test
    void webpStillRejectsMisdeclaredMimeAndCorruptedBytes() {
        assertThatThrownBy(() -> validator.inspect(WEBP, "image/png", AssetUsage.IMAGE))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> validator.inspect(new byte[] {1, 2, 3}, "image/webp", AssetUsage.IMAGE))
                .isInstanceOf(ApiException.class);
    }
}
