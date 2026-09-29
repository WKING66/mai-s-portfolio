package dev.amai.portfolio.storage;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** 将本地文件路径与 OSS 对象键约束为同一套安全格式。 */
public final class ObjectStorageKeyValidator {
    private static final int MAX_KEY_BYTES = 1023;
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:/.*");

    private ObjectStorageKeyValidator() {
    }

    public static String validate(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_REQUIRED);
        }
        String key = objectKey.strip();
        // 对象键是资源身份的一部分，不能通过静默裁剪把不同输入映射到同一对象。
        if (!key.equals(objectKey)) {
            throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_INVALID);
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_TOO_LONG);
        }
        if (key.startsWith("/") || WINDOWS_ABSOLUTE_PATH.matcher(key).matches()
            || key.indexOf('\\') >= 0 || key.indexOf(':') >= 0 || hasControlCharacter(key)) {
            throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_INVALID);
        }
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_INVALID);
            }
        }
        return key;
    }

    private static boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(character -> character <= 31 || character == 127);
    }
}
