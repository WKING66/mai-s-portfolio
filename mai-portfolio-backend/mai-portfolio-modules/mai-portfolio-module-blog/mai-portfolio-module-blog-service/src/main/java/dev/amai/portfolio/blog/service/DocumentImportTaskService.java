package dev.amai.portfolio.blog.service;

import dev.amai.portfolio.blog.entity.domain.DocumentImportTaskDO;
import java.util.List;

/** 同步导入的可追溯记录，与文档内容生命周期分离。 */
public interface DocumentImportTaskService {
    DocumentImportTaskDO begin(long uploader, String requestId, String filename, long size);

    void recordSource(long id, String sha256);

    void succeeded(long id, long documentId, String sha256);

    void failed(long id, RuntimeException failure);

    DocumentImportTaskDO get(long id);

    List<DocumentImportTaskDO> recent(Long documentId);
}
