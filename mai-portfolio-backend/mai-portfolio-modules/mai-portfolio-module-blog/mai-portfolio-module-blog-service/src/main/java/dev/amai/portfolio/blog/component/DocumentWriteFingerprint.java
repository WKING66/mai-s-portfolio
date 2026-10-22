package dev.amai.portfolio.blog.component;

import dev.amai.portfolio.blog.entity.request.BlogRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 写请求重试摘要；长度分隔避免字符串拼接歧义，不保存正文或凭据副本。 */
public final class DocumentWriteFingerprint {
    private DocumentWriteFingerprint() {}

    public static String save(BlogRequest request) {
        var digest = digest();
        add(digest, "SAVE");
        add(digest, request.slug());
        add(digest, request.title());
        add(digest, request.summary());
        add(digest, request.content());
        add(digest, Boolean.toString(request.allowDownload()));
        add(digest, Boolean.toString(request.remoteRightsConfirmed()));
        request.tagIds().forEach(id -> add(digest, id.toString()));
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String action(String action) {
        return bytes(action.getBytes(StandardCharsets.UTF_8));
    }

    public static String bytes(byte[] input) {
        return HexFormat.of().formatHex(digest().digest(input));
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM must support SHA-256", failure);
        }
    }
}
