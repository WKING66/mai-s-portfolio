package dev.amai.portfolio.document.api;

import dev.amai.portfolio.common.PageRequest;
import dev.amai.portfolio.common.PageVO;
import dev.amai.portfolio.document.entity.vo.DocumentImportTaskVo;

/** 同步导入的可追溯记录，与文档内容生命周期分离。 */
public interface DocumentImportTaskService {
    long begin(long uploader, String requestId, String filename, long size);

    void recordSource(long id, String sha256);

    void succeeded(long id, long documentId, String sha256);

    void failed(long id, RuntimeException failure);

    DocumentImportTaskVo get(long id);

    PageVO<DocumentImportTaskVo> page(Long documentId, PageRequest pagination);
}
