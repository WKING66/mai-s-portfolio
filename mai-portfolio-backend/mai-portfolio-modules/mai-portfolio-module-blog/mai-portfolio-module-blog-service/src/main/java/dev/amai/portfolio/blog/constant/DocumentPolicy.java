package dev.amai.portfolio.blog.constant;

/** 当前文档协议及持久化约定；可部署调整的数量、字节和超时上限由 DocumentProperties 承担。 */
public final class DocumentPolicy {
    public static final String REQUEST_ID_PATTERN =
            "[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";

    public static final int MAX_SOURCE_FILENAME_LENGTH = 255;

    public static final String MARKDOWN_EXTENSION = ".md";

    /** 编辑器上传后使用的临时引用；保存时转成稳定的 resource://key。 */
    public static final String ASSET_REFERENCE_PREFIX = "asset://";

    /** 对齐 document_resource 的 resource_key 与 original_uri 字段容量。 */
    public static final int MAX_RESOURCE_KEY_LENGTH = 36;

    public static final int MAX_RESOURCE_URI_LENGTH = 2048;

    /** 资源失败诊断仅保存安全摘要，与数据库 error_message 容量保持一致。 */
    public static final int MAX_RESOURCE_ERROR_LENGTH = 512;

    /** 网络资源读取和 ZIP 复制的单次缓冲量，不是文件大小上限。 */
    public static final int IO_BUFFER_BYTES = 16 * 1024;

    private DocumentPolicy() {}
}
