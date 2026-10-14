package dev.amai.portfolio.blog.service.impl;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.*;
import static org.springframework.transaction.annotation.Isolation.REPEATABLE_READ;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.blog.mapper.*;
import dev.amai.portfolio.blog.service.DocumentService;
import dev.amai.portfolio.web.exception.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;

/** 持久化文档身份、不可变内容版本及版本资源关系；博客发布指针由 BlogService 管理。 */
@Service
public class DocumentServiceImpl implements DocumentService {
    // 混合语言正文的粗略阅读时间，仅作展示元数据，不参与发布或权限判断。
    private static final int READING_CHARACTERS_PER_MINUTE = 500;
    private final DocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final DocumentResourceMapper resources;

    public DocumentServiceImpl(
            DocumentMapper documents,
            DocumentVersionMapper versions,
            DocumentResourceMapper resources) {
        this.documents = documents;
        this.versions = versions;
        this.resources = resources;
    }

    @Override
    public java.util.Map<Long, DocumentSummary> summaries(java.util.Map<Long, Long> requested) {
        // null 指针读取当前草稿，明确的版本 ID 读取公开快照；列表只取摘要字段，不加载正文。
        if (requested.isEmpty()) return java.util.Map.of();
        var docs =
                documents.selectList(
                        Wrappers.<DocumentDO>lambdaQuery()
                                .in(DocumentDO::getId, requested.keySet()));
        java.util.Map<Long, Long> pointers = new java.util.HashMap<>();
        docs.forEach(
                d ->
                        pointers.put(
                                d.getId(),
                                requested.get(d.getId()) == null
                                        ? d.getCurrentVersionId()
                                        : requested.get(d.getId())));
        var ids = pointers.values().stream().filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) return java.util.Map.of();
        var rows =
                versions.selectList(
                        Wrappers.<DocumentVersionDO>lambdaQuery()
                                .select(
                                        DocumentVersionDO::getId,
                                        DocumentVersionDO::getDocumentId,
                                        DocumentVersionDO::getVersionNumber,
                                        DocumentVersionDO::getTitle,
                                        DocumentVersionDO::getSummary)
                                .in(DocumentVersionDO::getId, ids));
        java.util.Map<Long, Long> locks = new java.util.HashMap<>();
        docs.forEach(d -> locks.put(d.getId(), d.getLockVersion()));
        java.util.Map<Long, DocumentSummary> result = new java.util.HashMap<>();
        rows.forEach(
                v -> {
                    if (java.util.Objects.equals(pointers.get(v.getDocumentId()), v.getId()))
                        result.put(
                                v.getDocumentId(),
                                new DocumentSummary(
                                        v.getDocumentId(),
                                        requested.get(v.getDocumentId()) == null
                                                ? locks.get(v.getDocumentId())
                                                : v.getVersionNumber(),
                                        v.getTitle(),
                                        v.getSummary()));
                });
        return result;
    }

    @Override
    @Transactional
    public Document create(
            long owner, int origin, String title, String summary, PreparedDocument prepared) {
        var document = new DocumentDO();
        document.setOwnerId(owner);
        document.setOriginType(origin);
        document.setLockVersion(INITIAL_VERSION);
        document.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        document.setUpdatedAt(document.getCreatedAt());
        documents.insert(document);
        return persist(document, title, summary, prepared, owner);
    }

    @Override
    @Transactional
    public Document append(
            long id,
            long expected,
            long editor,
            String title,
            String summary,
            PreparedDocument prepared) {
        advanceLock(id, expected);
        // 乐观锁成功后再追加版本；旧版本内容和资源关系从不原地修改。
        var document = required(id);
        return persist(document, title, summary, prepared, editor);
    }

    private Document persist(
            DocumentDO document,
            String title,
            String summary,
            PreparedDocument prepared,
            long editor) {
        var version = new DocumentVersionDO();
        version.setDocumentId(document.getId());
        version.setVersionNumber(document.getLockVersion());
        version.setTitle(title);
        version.setSummary(summary);
        version.setMarkdownContent(prepared.content());
        version.setReadingTimeMinutes(
                (short)
                        Math.min(
                                Short.MAX_VALUE,
                                Math.max(
                                        1,
                                        (prepared.content()
                                                                .codePointCount(
                                                                        0,
                                                                        prepared.content().length())
                                                        + READING_CHARACTERS_PER_MINUTE
                                                        - 1)
                                                / READING_CHARACTERS_PER_MINUTE)));
        version.setCreatedBy(editor);
        version.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        versions.insert(version);
        int order = 0;
        for (var r : prepared.resources()) {
            var row = new DocumentResourceDO();
            row.setDocumentVersionId(version.getId());
            row.setMediaId(r.assetId());
            row.setRole(BODY_RESOURCE_ROLE);
            row.setResourceKey(r.key());
            row.setResourceType(r.type().code());
            row.setSourceKind(r.source().code());
            row.setSourceReference(r.originalUri());
            row.setStatus(r.status().code());
            row.setSortOrder(order++);
            resources.insert(row);
        }
        // 文档版本与全部资源关系写入后才切换 current_version_id，统一处于调用方事务中。
        documents.update(
                null,
                Wrappers.<DocumentDO>lambdaUpdate()
                        .eq(DocumentDO::getId, document.getId())
                        .set(DocumentDO::getCurrentVersionId, version.getId()));
        return new Document(
                document.getId(),
                version.getId(),
                version.getVersionNumber(),
                document.getLockVersion(),
                title,
                summary,
                prepared.content(),
                prepared.resources());
    }

    @Override
    @Transactional(
            readOnly = true,
            isolation = REPEATABLE_READ)
    public Document read(long id, Long versionId) {
        // 可重复读保证身份指针、版本和资源关系来自同一个数据库快照。
        var document = required(id);
        Long pointer = versionId == null ? document.getCurrentVersionId() : versionId;
        var v = pointer == null ? null : versions.selectById(pointer);
        if (v == null || !v.getDocumentId().equals(id))
            throw new ApiException(ApiErrorCode.NOT_FOUND, "文档版本不存在");
        var rows =
                resources.selectList(
                        Wrappers.<DocumentResourceDO>lambdaQuery()
                                .eq(DocumentResourceDO::getDocumentVersionId, pointer)
                                .orderByAsc(DocumentResourceDO::getSortOrder));
        return new Document(
                id,
                v.getId(),
                v.getVersionNumber(),
                document.getLockVersion(),
                v.getTitle(),
                v.getSummary(),
                v.getMarkdownContent(),
                rows.stream()
                        .map(
                                r ->
                                        new DocumentResource(
                                                r.getResourceKey(),
                                                ResourceType.from(r.getResourceType()),
                                                ResourceSource.from(r.getSourceKind()),
                                                r.getSourceReference(),
                                                r.getMediaId(),
                                                ResourceStatus.from(r.getStatus())))
                        .toList());
    }

    @Override
    public long lockVersion(long id) {
        return required(id).getLockVersion();
    }

    @Override
    @Transactional
    public void advanceLock(long id, long expected) {
        // 保存、发布和下架共用 CAS；发布会增加锁计数，但不产生新的正文版本。
        if (documents.update(
                        null,
                        Wrappers.<DocumentDO>lambdaUpdate()
                                .eq(DocumentDO::getId, id)
                                .eq(DocumentDO::getLockVersion, expected)
                                .set(DocumentDO::getLockVersion, expected + 1)
                                .set(DocumentDO::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)))
                != 1)
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "文档已被修改，请重新读取后保存");
    }

    private DocumentDO required(long id) {
        var row = documents.selectById(id);
        if (row == null) throw new ApiException(ApiErrorCode.NOT_FOUND, "文档不存在");
        return row;
    }
}
