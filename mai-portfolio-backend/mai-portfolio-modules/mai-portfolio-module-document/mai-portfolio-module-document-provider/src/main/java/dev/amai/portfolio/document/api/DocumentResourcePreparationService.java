package dev.amai.portfolio.document.api;

import dev.amai.portfolio.document.entity.domain.DocumentInputResource;
import dev.amai.portfolio.document.entity.domain.DocumentResource;
import dev.amai.portfolio.document.entity.domain.PreparedDocument;
import java.util.Map;

/** 归档正文资源并规范化上传文件的相对路径。 */
public interface DocumentResourcePreparationService {
    /** previous 仅为当前文档的引用；返回的新增资产用于调用方的失败补偿。 */
    PreparedDocument prepare(String content, Map<String, DocumentResource> previous,
            Map<String, DocumentInputResource> local, boolean remoteRightsConfirmed, long uploader);

    String localPath(String uri);
}
