package dev.amai.portfolio.blog.service;

import dev.amai.portfolio.blog.entity.domain.*;

/** 消费已授权的统一 Document，不重新解析原始文件，也不决定博客访问权限。 */
public interface DocumentExportService {
    /** 携带资源且确有资源时生成 ZIP，否则生成 .md；计划中的 Writer 流式写入资源。 */
    DocumentExport prepare(Document document, boolean includeResources);
}
