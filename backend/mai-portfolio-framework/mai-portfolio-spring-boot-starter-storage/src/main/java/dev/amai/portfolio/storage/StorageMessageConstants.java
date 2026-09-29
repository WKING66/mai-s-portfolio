package dev.amai.portfolio.storage;

/** 对象存储边界及实现共用的稳定提示，避免跨模块依赖应用层常量。 */
public final class StorageMessageConstants {
    public static final String OBJECT_KEY_REQUIRED = "对象存储键不能为空";
    public static final String OBJECT_KEY_INVALID = "对象存储键格式不合法";
    public static final String OBJECT_KEY_TOO_LONG = "对象存储键不能超过 1023 字节";
    public static final String CONTENT_REQUIRED = "待存储内容不能为空";
    public static final String CONTENT_LENGTH_INVALID = "对象字节数不能为负数";
    public static final String CONTENT_TYPE_REQUIRED = "对象内容类型不能为空";
    public static final String CONTENT_LENGTH_MISMATCH = "实际对象字节数与声明值不一致";
    public static final String OBJECT_ALREADY_EXISTS = "对象已存在，禁止覆盖";
    public static final String OBJECT_NOT_FOUND = "对象不存在";
    public static final String DIRECTORY_INITIALIZATION_FAILED = "本地对象存储目录初始化失败";
    public static final String WRITE_FAILED = "对象写入失败";
    public static final String READ_FAILED = "对象读取失败";
    public static final String DELETE_FAILED = "对象删除失败";
    public static final String OSS_CONFIGURATION_INCOMPLETE =
        "OSS 模式必须配置 endpoint、bucket-name、access-key-id 和 access-key-secret";
    public static final String OSS_ENDPOINT_HTTPS_REQUIRED =
        "OSS endpoint 必须是有效的 HTTPS 根地址";

    private StorageMessageConstants() {
    }
}
