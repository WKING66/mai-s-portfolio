package dev.amai.portfolio.blog.component;

import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.enums.ResourceSource;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.Base64;
import java.util.Locale;
import org.springframework.http.MediaType;

/** 解码 Markdown 内嵌图片，不负责图片格式信任、上传或绑定；原始 Base64 不进入来源字段。 */
final class DocumentDataUri {
    private static final String PREFIX = "data:";
    private static final String BASE64_MARKER = ";base64";
    private static final int MAX_HEADER_LENGTH = 128;

    private DocumentDataUri() {}

    static boolean matches(String uri) {
        return uri.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    /** 来源仅留 MIME 描述，既不持久化内嵌内容，也不因它超过普通 URL 长度而拒绝。 */
    static String sourceReference(String uri) {
        int comma = uri.indexOf(',');
        return comma > PREFIX.length() && comma <= MAX_HEADER_LENGTH
                ? uri.substring(0, comma) : "data:[invalid image]";
    }

    static DocumentInputResource decode(String uri, AssetUsage usage) {
        int comma = uri.indexOf(',');
        if (usage != AssetUsage.IMAGE || comma <= PREFIX.length() || comma > MAX_HEADER_LENGTH) {
            throw invalid("内嵌资源必须是 data:image/...;base64 格式的图片");
        }
        String header = uri.substring(PREFIX.length(), comma).toLowerCase(Locale.ROOT);
        if (!header.startsWith("image/") || !header.endsWith(BASE64_MARKER)) {
            throw invalid("内嵌图片须使用 data:image/...;base64 格式");
        }
        String mime;
        try {
            var declared = MediaType.parseMediaType(
                    header.substring(0, header.length() - BASE64_MARKER.length()));
            mime = declared.getType() + "/" + declared.getSubtype();
        } catch (IllegalArgumentException failure) {
            throw invalid("内嵌图片的 MIME 声明无效");
        }
        // 解码前按单文件上限限制 Base64 分配量，解码后再次检查真实字节数。
        int encodedLength = uri.length() - comma - 1;
        long maxEncodedLength = 4L * ((usage.maxBytes() + 2L) / 3);
        if (encodedLength > maxEncodedLength) {
            throw invalid("内嵌图片超过当前用途的大小限制");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(uri.substring(comma + 1));
        } catch (IllegalArgumentException failure) {
            throw invalid("内嵌图片的 Base64 内容损坏或编码无效");
        }
        if (bytes.length == 0 || bytes.length > usage.maxBytes()) {
            throw invalid("内嵌图片为空或超过当前用途的大小限制");
        }
        return new DocumentInputResource("embedded-image", mime, bytes, ResourceSource.EMBEDDED);
    }

    private static ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
