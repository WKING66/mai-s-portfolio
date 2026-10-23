package dev.amai.portfolio.blog.service;

import dev.amai.portfolio.blog.entity.domain.DocumentImportTaskDO;
import dev.amai.portfolio.blog.entity.vo.DocumentImportTaskVo;
import dev.amai.portfolio.common.PageRequest;
import dev.amai.portfolio.common.PageVO;

/** 同步导入的可追溯记录，与文档内容生命周期分离。 */
public interface DocumentImportTaskService {
    DocumentImportTaskDO begin(long uploader, String requestId, String filename, long size);

    void recordSource(long id, String sha256);

    void succeeded(long id, long documentId, String sha256);

    void failed(long id, RuntimeException failure);

    DocumentImportTaskVo get(long id);

    PageVO<DocumentImportTaskVo> page(Long documentId, PageRequest pagination);
}
