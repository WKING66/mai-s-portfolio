package dev.amai.portfolio.storage;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** 将本地文件路径与 OSS 对象键约束为同一套安全格式。 */
public final class ObjectStorageKeyValidator {
    private static final int MAX_KEY_BYTES = 1023;
    private static final int MAX_ASCII_CONTROL_CODE_POINT = 31;
    private static final int ASCII_DELETE_CODE_POINT = 127;
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile("^[A-Za-z]:/.*");

    private ObjectStorageKeyValidator() {}

    public static String validate(String objectKey) {
        // 1. 检查身份是否完整；禁止用裁剪或规范化替换调用方传入的对象身份。
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_REQUIRED);
        }
        String key = objectKey.strip();
        // 对象键是资源身份的一部分，不能通过静默裁剪把不同输入映射到同一对象。
        if (!key.equals(objectKey)) {
            throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_INVALID);
        }
        // 2. 长度按 UTF-8 字节计算；拒绝绝对路径、平台路径标识及控制字符。
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_TOO_LONG);
        }
        if (key.startsWith("/")
                || WINDOWS_ABSOLUTE_PATH.matcher(key).matches()
                || key.indexOf('\\') >= 0
                || key.indexOf(':') >= 0
                || hasControlCharacter(key)) {
            throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_INVALID);
        }
        // 3. 逐段拒绝空段和相对跳转，本地存储与 OSS 使用同一套键约束。
        for (String segment : key.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(StorageMessageConstants.OBJECT_KEY_INVALID);
            }
        }
        return key;
    }

    private static boolean hasControlCharacter(String value) {
        return value.codePoints()
                .anyMatch(
                        character ->
                                character <= MAX_ASCII_CONTROL_CODE_POINT
                                        || character == ASCII_DELETE_CODE_POINT);
    }
}
