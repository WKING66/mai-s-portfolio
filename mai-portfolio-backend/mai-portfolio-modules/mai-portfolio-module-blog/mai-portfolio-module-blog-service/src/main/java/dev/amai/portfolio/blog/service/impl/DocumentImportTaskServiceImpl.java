package dev.amai.portfolio.blog.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.blog.entity.domain.DocumentImportTaskDO;
import dev.amai.portfolio.blog.enums.ImportTaskStatus;
import dev.amai.portfolio.blog.mapper.DocumentImportTaskMapper;
import dev.amai.portfolio.blog.service.DocumentImportTaskService;
import dev.amai.portfolio.system.api.OwnerAuthorizationService;
import dev.amai.portfolio.web.exception.*;
import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.*;

/** PROCESSING/失败单独提交；成功状态必须与博客、内容及资源关系共同提交。 */
@Service
public class DocumentImportTaskServiceImpl implements DocumentImportTaskService {
    private static final int SOURCE_FORMAT_MARKDOWN = 0;

    private static final int RECENT_TASK_LIMIT = 50;

    private final DocumentImportTaskMapper documentImportTaskMapper;

    private final OwnerAuthorizationService ownerAuthorizationService;

    public DocumentImportTaskServiceImpl(DocumentImportTaskMapper documentImportTaskMapper,
            OwnerAuthorizationService ownerAuthorizationService) {
        this.documentImportTaskMapper = documentImportTaskMapper;
        this.ownerAuthorizationService = ownerAuthorizationService;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DocumentImportTaskDO begin(long uploader, String requestId, String filename, long size) {
        var previous = documentImportTaskMapper.selectOne(Wrappers.<DocumentImportTaskDO>lambdaQuery()
                .eq(DocumentImportTaskDO::getUploaderAccountId, uploader)
                .eq(DocumentImportTaskDO::getRequestId, requestId));
        if (previous != null) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT,
                    "该导入请求已有记录，请读取导入记录确认结果后再操作，taskId=" + previous.getId());
        }
        var task = new DocumentImportTaskDO();
        task.setUploaderAccountId(uploader);
        task.setRequestId(requestId);
        task.setOriginalFilename(filename);
        task.setSourceSize(size);
        task.setSourceFormat(SOURCE_FORMAT_MARKDOWN);
        task.setStatus(ImportTaskStatus.PROCESSING.name());
        task.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        task.setUpdatedAt(task.getCreatedAt());
        try {
            documentImportTaskMapper.insert(task);
        } catch (DuplicateKeyException failure) {
            // 相同请求并发到达时唯一索引只负责竞争仲裁，业务层返回可理解的冲突。
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "该导入请求已存在，请先查询最近导入记录");
        }
        return task;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSource(long id, String sha256) {
        int updated = documentImportTaskMapper.update(null, Wrappers.<DocumentImportTaskDO>lambdaUpdate()
                .eq(DocumentImportTaskDO::getId, id)
                .eq(DocumentImportTaskDO::getStatus, ImportTaskStatus.PROCESSING.name())
                .set(DocumentImportTaskDO::getSourceSha256, sha256)
                .set(DocumentImportTaskDO::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (updated != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "导入记录已结束，不能覆盖来源信息");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void succeeded(long id, long documentId, String sha256) {
        if (documentImportTaskMapper.succeeded(id, documentId, sha256) != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "导入记录已结束，不能重复提交");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(long id, RuntimeException failure) {
        String code = failure instanceof ApiException api ? api.code().name() : ApiErrorCode.INTERNAL_ERROR.name();
        String message = failure instanceof ApiException ? failure.getMessage() : "导入未完成，请检查服务日志";
        // 只接受业务边界已经裁剪的说明；数据库/SDK 原始异常不写入任务记录。
        documentImportTaskMapper.failed(id, code, message);
    }

    @Override
    public DocumentImportTaskDO get(long id) {
        ownerAuthorizationService.requireOwner();
        var task = documentImportTaskMapper.selectById(id);
        if (task == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "导入记录不存在");
        }
        return task;
    }

    @Override
    public List<DocumentImportTaskDO> recent(Long documentId) {
        ownerAuthorizationService.requireOwner();
        return documentImportTaskMapper.selectList(Wrappers.<DocumentImportTaskDO>lambdaQuery()
                .eq(documentId != null, DocumentImportTaskDO::getDocumentId, documentId)
                .orderByDesc(DocumentImportTaskDO::getId).last("LIMIT " + RECENT_TASK_LIMIT));
    }
}
