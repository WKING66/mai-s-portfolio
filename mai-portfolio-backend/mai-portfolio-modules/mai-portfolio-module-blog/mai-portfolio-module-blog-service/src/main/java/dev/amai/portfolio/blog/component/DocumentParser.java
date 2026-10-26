package dev.amai.portfolio.blog.component;

import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.entity.domain.ParsedDocument;
import java.util.Map;

/** 仅解析输入，不访问账号、数据库或 OSS。 */
public interface DocumentParser {
    /** resources 是正文相对路径到输入文件的映射；实现不得直接上传或绑定资源。 */
    ParsedDocument parse(
            String filename, byte[] input, Map<String, DocumentInputResource> resources);
}
