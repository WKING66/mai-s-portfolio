package dev.amai.portfolio.blog.service.impl;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.*;
import static org.springframework.transaction.annotation.Isolation.REPEATABLE_READ;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.blog.convert.DocumentConvert;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.blog.mapper.*;
import dev.amai.portfolio.blog.service.DocumentService;
import dev.amai.portfolio.web.exception.*;
import java.time.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 持久化文档身份、不可变内容版本及版本资源关系；博客发布指针由 BlogService 管理。 */
@Service
public class DocumentServiceImpl implements DocumentService {
    // 混合语言正文的粗略阅读时间，仅作展示元数据，不参与发布或权限判断。
    private static final int READING_CHARACTERS_PER_MINUTE = 500;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentResourceMapper documentResourceMapper;

    public DocumentServiceImpl(
            DocumentMapper documentMapper,
            DocumentVersionMapper documentVersionMapper,
            DocumentResourceMapper documentResourceMapper) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentResourceMapper = documentResourceMapper;
    }

    @Override
    public Map<Long, DocumentSummary> summaries(Map<Long, Long> requested) {
        // null 指针读取当前草稿，明确的版本 ID 读取公开快照；列表只取摘要字段，不加载正文。
        if (requested.isEmpty()) {
            return Map.of();
        }
        var documentIdentities =
                documentMapper.selectList(
                        Wrappers.<DocumentDO>lambdaQuery()
                                .in(DocumentDO::getId, requested.keySet()));
        Map<Long, Long> versionIdsByDocument = new HashMap<>();
        documentIdentities.forEach(
                documentIdentity ->
                        versionIdsByDocument.put(
                                documentIdentity.getId(),
                                ObjectUtil.defaultIfNull(
                                        requested.get(documentIdentity.getId()),
                                        documentIdentity.getCurrentVersionId())));
        var versionIds = versionIdsByDocument.values().stream().filter(Objects::nonNull).toList();
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        var documentVersions =
                documentVersionMapper.selectList(
                        Wrappers.<DocumentVersionDO>lambdaQuery()
                                .select(
                                        DocumentVersionDO::getId,
                                        DocumentVersionDO::getDocumentId,
                                        DocumentVersionDO::getVersionNumber,
                                        DocumentVersionDO::getTitle,
                                        DocumentVersionDO::getSummary)
                                .in(DocumentVersionDO::getId, versionIds));
        Map<Long, Long> lockVersionsByDocument = new HashMap<>();
        documentIdentities.forEach(
                documentIdentity ->
                        lockVersionsByDocument.put(
                                documentIdentity.getId(), documentIdentity.getLockVersion()));
        Map<Long, DocumentSummary> summariesByDocument = new HashMap<>();
        documentVersions.forEach(
                documentVersion -> {
                    if (Objects.equals(
                            versionIdsByDocument.get(documentVersion.getDocumentId()),
                            documentVersion.getId())) {
                        summariesByDocument.put(
                                documentVersion.getDocumentId(),
                                DocumentConvert.MAPPER.to(
                                        documentVersion,
                                        requested.get(documentVersion.getDocumentId()) == null
                                                ? lockVersionsByDocument.get(
                                                        documentVersion.getDocumentId())
                                                : documentVersion.getVersionNumber()));
                    }
                });
        return summariesByDocument;
    }

    @Override
    @Transactional
    public Document create(
            long owner, int origin, String title, String summary, PreparedDocument prepared) {
        // 1. 创建文档身份及初始锁版本；身份、正文版本和资源关系由同一事务提交。
        var document = new DocumentDO();
        document.setOwnerId(owner);
        document.setOriginType(origin);
        document.setLockVersion(INITIAL_VERSION);
        document.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        document.setUpdatedAt(document.getCreatedAt());
        documentMapper.insert(document);
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
        // 1. 新增不可变正文快照，不原地修改旧版本内容。
        int contentCodePoints = prepared.content().codePointCount(0, prepared.content().length());
        int roundedReadingMinutes =
                (contentCodePoints + READING_CHARACTERS_PER_MINUTE - 1)
                        / READING_CHARACTERS_PER_MINUTE;
        short readingTimeMinutes =
                (short) Math.min(Short.MAX_VALUE, Math.max(1, roundedReadingMinutes));
        var documentVersion =
                DocumentConvert.MAPPER.to(
                        document,
                        prepared,
                        title,
                        summary,
                        editor,
                        readingTimeMinutes,
                        LocalDateTime.now(ZoneOffset.UTC));
        documentVersionMapper.insert(documentVersion);
        // 2. 按准备结果写入该版本独立的资源关系，失败关系允许不绑定 Asset。
        int resourceOrder = 0;
        for (var resource : prepared.resources()) {
            var documentResourceRelation =
                    DocumentConvert.MAPPER.to(
                            resource, documentVersion.getId(), BODY_RESOURCE_ROLE, resourceOrder++);
            documentResourceMapper.insert(documentResourceRelation);
        }
        // 3. 文档版本与全部资源关系写入后才切换 current_version_id，统一处于调用方事务中。
        documentMapper.update(
                null,
                Wrappers.<DocumentDO>lambdaUpdate()
                        .eq(DocumentDO::getId, document.getId())
                        .set(DocumentDO::getCurrentVersionId, documentVersion.getId()));
        return DocumentConvert.MAPPER.to(document, documentVersion, prepared.resources());
    }

    @Override
    @Transactional(readOnly = true, isolation = REPEATABLE_READ)
    public Document read(long id, Long versionId) {
        // 可重复读保证身份指针、版本和资源关系来自同一个数据库快照。
        var document = required(id);
        Long versionIdPointer = ObjectUtil.defaultIfNull(versionId, document.getCurrentVersionId());
        var documentVersion =
                versionIdPointer == null
                        ? null
                        : documentVersionMapper.selectById(versionIdPointer);
        if (documentVersion == null || !documentVersion.getDocumentId().equals(id)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "文档版本不存在");
        }
        var versionResources =
                documentResourceMapper.selectList(
                        Wrappers.<DocumentResourceDO>lambdaQuery()
                                .eq(DocumentResourceDO::getDocumentVersionId, versionIdPointer)
                                .orderByAsc(DocumentResourceDO::getSortOrder));
        return DocumentConvert.MAPPER.to(
                document, documentVersion, DocumentConvert.MAPPER.to(versionResources));
    }

    @Override
    public long lockVersion(long id) {
        return required(id).getLockVersion();
    }

    @Override
    @Transactional
    public void advanceLock(long id, long expected) {
        // 保存、发布和下架共用 CAS；发布会增加锁计数，但不产生新的正文版本。
        if (documentMapper.update(
                        null,
                        Wrappers.<DocumentDO>lambdaUpdate()
                                .eq(DocumentDO::getId, id)
                                .eq(DocumentDO::getLockVersion, expected)
                                .set(DocumentDO::getLockVersion, expected + 1)
                                .set(DocumentDO::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)))
                != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "文档已被修改，请重新读取后保存");
        }
    }

    private DocumentDO required(long id) {
        var documentIdentity = documentMapper.selectById(id);
        if (documentIdentity == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "文档不存在");
        }
        return documentIdentity;
    }
}
