package dev.amai.portfolio.blog.constant;

/** 当前文档协议及持久化约定；可部署调整的数量、字节和超时上限由 DocumentProperties 承担。 */
public final class DocumentPolicy {
    public static final String MARKDOWN_EXTENSION = ".md";

    /** 编辑器上传后使用的临时引用；保存时转成稳定的 resource://key。 */
    public static final String ASSET_REFERENCE_PREFIX = "asset://";

    /** 对齐 document_version_asset 的 resource_key 与 source_reference 字段容量。 */
    public static final int MAX_RESOURCE_KEY_LENGTH = 36;

    public static final int MAX_RESOURCE_URI_LENGTH = 2048;

    /** 旧表 role=0 表示正文资源关系；图片/附件由 resource_type 区分。 */
    public static final int BODY_RESOURCE_ROLE = 0;

    public static final long INITIAL_VERSION = 1L;

    /** 网络资源读取和 ZIP 复制的单次缓冲量，不是文件大小上限。 */
    public static final int IO_BUFFER_BYTES = 16 * 1024;

    private DocumentPolicy() {}
}
