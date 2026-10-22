package dev.amai.portfolio.blog.service.impl;

import static org.springframework.transaction.annotation.Isolation.REPEATABLE_READ;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.blog.convert.DocumentConvert;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.blog.mapper.*;
import dev.amai.portfolio.blog.service.DocumentService;
import dev.amai.portfolio.system.api.TagData;
import dev.amai.portfolio.web.exception.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 文档只持有两个当前内容槽位；所有写动作共用身份行锁和 revision。 */
@Service
public class DocumentServiceImpl implements DocumentService {
    private static final int READING_CHARACTERS_PER_MINUTE = 500;

    private final DocumentMapper documentMapper;

    private final DocumentContentMapper documentContentMapper;

    private final DocumentResourceMapper documentResourceMapper;

    private final DocumentContentTagMapper documentContentTagMapper;

    private final AssetQueryService assetQueryService;

    public DocumentServiceImpl(DocumentMapper documentMapper,
            DocumentContentMapper documentContentMapper,
            DocumentResourceMapper documentResourceMapper,
            DocumentContentTagMapper documentContentTagMapper,
            AssetQueryService assetQueryService) {
        this.documentMapper = documentMapper;
        this.documentContentMapper = documentContentMapper;
        this.documentResourceMapper = documentResourceMapper;
        this.documentContentTagMapper = documentContentTagMapper;
        this.assetQueryService = assetQueryService;
    }

    @Override
    @Transactional(readOnly = true, isolation = REPEATABLE_READ)
    public Map<Long, DocumentSummary> summaries(Set<Long> ids, boolean manage) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var identities = documentMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(DocumentDO::getId, value -> value));
        var contents = documentContentMapper.selectList(Wrappers.<DocumentContentDO>lambdaQuery()
                .select(DocumentContentDO::getId, DocumentContentDO::getDocumentId,
                        DocumentContentDO::getStage, DocumentContentDO::getTitle,
                        DocumentContentDO::getSummary)
                .in(DocumentContentDO::getDocumentId, ids));
        Map<Long, DocumentSummary> result = new HashMap<>();
        for (long id : ids) {
            var slots = contents.stream().filter(value -> value.getDocumentId() == id).toList();
            var selected = select(slots, manage);
            var identity = identities.get(id);
            if (selected != null && identity != null) {
                result.put(id, new DocumentSummary(id, selected.getId(),
                        manage ? identity.getRevision() : null,
                        ContentStage.valueOf(selected.getStage()), manage && hasDraft(slots),
                        hasPublished(slots), selected.getTitle(), selected.getSummary()));
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true, isolation = REPEATABLE_READ)
    public Document read(long id, boolean manage) {
        // 同一快照读取身份、槽位及关系；并发发布不会拼出新正文与旧资源。
        return readCurrent(required(id), manage);
    }

    private Document readCurrent(DocumentDO identity, boolean manage) {
        var slots = slots(identity.getId());
        var content = select(slots, manage);
        if (content == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "所需文档内容不存在");
        }
        var resources = documentResourceMapper.selectList(Wrappers.<DocumentResourceDO>lambdaQuery()
                .eq(DocumentResourceDO::getContentId, content.getId())
                .orderByAsc(DocumentResourceDO::getSortOrder, DocumentResourceDO::getId));
        var tags = documentContentTagMapper.selectList(Wrappers.<DocumentContentTagDO>lambdaQuery()
                .eq(DocumentContentTagDO::getContentId, content.getId())
                .orderByAsc(DocumentContentTagDO::getId)).stream()
                .map(tag -> new TagData(tag.getTagId(), tag.getName(), tag.getSlug(),
                        tag.getKind(), null, null)).toList();
        return DocumentConvert.MAPPER.to(identity, content, hasDraft(slots), hasPublished(slots),
                DocumentConvert.MAPPER.to(resources), tags);
    }

    @Override
    @Transactional(readOnly = true, isolation = REPEATABLE_READ)
    public Map<String, DocumentResource> references(long id) {
        required(id);
        Map<String, DocumentResource> result = new HashMap<>();
        // 正式正文先填充，草稿覆盖同 key；被草稿移除但正式正文仍使用的资源仍可复用。
        var contents = slots(id);
        contents.sort(Comparator.comparing(value -> ContentStage.DRAFT.name().equals(value.getStage())));
        for (var content : contents) {
            var relations = documentResourceMapper.selectList(Wrappers.<DocumentResourceDO>lambdaQuery()
                    .eq(DocumentResourceDO::getContentId, content.getId()));
            for (var relation : relations) {
                var resource = DocumentConvert.MAPPER.to(relation);
                result.put(resource.key(), resource);
            }
        }
        return result;
    }

    @Override
    public DocumentDO findCreation(long owner, String requestId) {
        return documentMapper.selectOne(Wrappers.<DocumentDO>lambdaQuery()
                .eq(DocumentDO::getOwnerId, owner)
                .eq(DocumentDO::getCreateRequestId, requestId));
    }

    @Override
    @Transactional
    public Document create(long owner, int origin, DocumentDraft draft) {
        var identity = new DocumentDO();
        identity.setOwnerId(owner);
        identity.setOriginType(origin);
        identity.setRevision(1L);
        identity.setCreateRequestId(draft.requestId());
        identity.setCreateRequestHash(draft.requestHash());
        identity.setLastRequestId(draft.requestId());
        identity.setLastRequestHash(draft.requestHash());
        identity.setCreatedAt(now());
        identity.setUpdatedAt(identity.getCreatedAt());
        documentMapper.insert(identity);
        persistDraft(identity, draft);
        return readCurrent(identity, true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Document save(long id, DocumentDraft draft) {
        // 调用方已通过 beginWrite 持有身份行锁，博客字段与草稿必须共同提交。
        persistDraft(required(id), draft);
        recordWrite(id, draft.requestId(), draft.requestHash());
        return readCurrent(required(id), true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean beginWrite(long id, long revision, String requestId, String requestHash) {
        // 1. 所有博客状态写入与关系替换先锁同一身份；事务结束才释放。
        var identity = documentMapper.selectOne(Wrappers.<DocumentDO>lambdaQuery()
                .eq(DocumentDO::getId, id).last("FOR UPDATE"));
        if (identity == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "文档不存在");
        }
        // 2. 同一已提交请求只返回当前结果，不能重复发布、重复上传或推进修订。
        if (Objects.equals(identity.getLastRequestId(), requestId)) {
            if (!Objects.equals(identity.getLastRequestHash(), requestHash)) {
                throw conflict("请求标识已用于不同内容，请生成新的 requestId");
            }
            return false;
        }
        if (identity.getRevision() != revision) {
            throw conflict("文档已被修改，请重新读取后操作");
        }
        return true;
    }

    private void persistDraft(DocumentDO identity, DocumentDraft draft) {
        // 1. 按资源 ID 排序锁定 READY 资产，与补偿删除使用同一锁协议。
        var resourceIds = draft.prepared().resources().stream()
                .filter(value -> value.assetId() != null)
                .map(DocumentResource::assetId).collect(Collectors.toSet());
        var assets = assetQueryService.lockReady(resourceIds);
        for (var resource : draft.prepared().resources()) {
            if (resource.assetId() != null) {
                var asset = assets.get(resource.assetId());
                AssetType expected = resource.type() == ResourceType.IMAGE
                        ? AssetType.IMAGE : AssetType.FILE;
                AssetUsage usage = resource.type() == ResourceType.IMAGE
                        ? AssetUsage.IMAGE : AssetUsage.ATTACHMENT;
                if (asset.type() != expected || asset.usage() != usage) {
                    throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "资源类型或用途与正文引用不符");
                }
            }
        }
        // 2. 重复保存原地更新唯一草稿；正式正文及其标签、资源关系不变。
        var existing = slot(identity.getId(), ContentStage.DRAFT);
        var content = DocumentConvert.MAPPER.to(draft);
        content.setDocumentId(identity.getId());
        content.setStage(ContentStage.DRAFT.name());
        int characters = content.getContent().codePointCount(0, content.getContent().length());
        content.setReadingTimeMinutes((short) Math.min(Short.MAX_VALUE,
                Math.max(1, (characters + READING_CHARACTERS_PER_MINUTE - 1)
                        / READING_CHARACTERS_PER_MINUTE)));
        content.setCreatedAt(existing == null ? now() : existing.getCreatedAt());
        content.setUpdatedAt(now());
        if (existing == null) {
            documentContentMapper.insert(content);
        } else {
            content.setId(existing.getId());
            documentContentMapper.updateById(content);
        }
        // 3. 关系以 key/标签 ID 做差量写入；未变化关系保留 ID，不重建整套历史。
        saveResources(content.getId(), draft.prepared().resources());
        saveTags(content.getId(), draft.tags());
    }

    private void saveResources(long contentId, List<DocumentResource> resources) {
        var old = documentResourceMapper.selectList(Wrappers.<DocumentResourceDO>lambdaQuery()
                .eq(DocumentResourceDO::getContentId, contentId)).stream()
                .collect(Collectors.toMap(DocumentResourceDO::getKey, value -> value));
        int order = 0;
        for (var resource : resources) {
            var relation = DocumentConvert.MAPPER.to(resource, contentId, order++);
            var existing = old.remove(resource.key());
            if (existing == null) {
                documentResourceMapper.insert(relation);
            } else if (!DocumentConvert.MAPPER.to(existing).equals(resource)
                    || !Objects.equals(existing.getSortOrder(), relation.getSortOrder())) {
                relation.setId(existing.getId());
                documentResourceMapper.updateById(relation);
            }
        }
        if (!old.isEmpty()) {
            documentResourceMapper.deleteByIds(old.values().stream().map(DocumentResourceDO::getId).toList());
        }
    }

    private void saveTags(long contentId, List<TagData> tags) {
        var old = documentContentTagMapper.selectList(Wrappers.<DocumentContentTagDO>lambdaQuery()
                .eq(DocumentContentTagDO::getContentId, contentId)).stream()
                .collect(Collectors.toMap(DocumentContentTagDO::getTagId, value -> value));
        for (var tag : tags) {
            var existing = old.remove(tag.id());
            var relation = DocumentConvert.MAPPER.to(tag, contentId);
            if (existing == null) {
                documentContentTagMapper.insert(relation);
            } else {
                relation.setId(existing.getId());
                documentContentTagMapper.updateById(relation);
            }
        }
        if (!old.isEmpty()) {
            documentContentTagMapper.deleteByIds(old.values().stream().map(DocumentContentTagDO::getId).toList());
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(long id) {
        var draft = slot(id, ContentStage.DRAFT);
        if (draft == null) {
            throw conflict("没有已保存的草稿；重新上架请使用恢复操作");
        }
        // 删除旧正式关系后提升草稿，全部在持有身份锁的同一事务中完成。
        var published = slot(id, ContentStage.PUBLISHED);
        if (published != null) {
            removeContent(published.getId());
        }
        draft.setStage(ContentStage.PUBLISHED.name());
        draft.setUpdatedAt(now());
        documentContentMapper.updateById(draft);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void discardDraft(long id) {
        var slots = slots(id);
        if (!hasPublished(slots)) {
            throw conflict("这篇文章只有草稿，请使用整篇删除");
        }
        var draft = select(slots, true);
        if (draft == null || !ContentStage.DRAFT.name().equals(draft.getStage())) {
            throw conflict("没有可丢弃的草稿");
        }
        removeContent(draft.getId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(long id) {
        var contents = slots(id);
        if (hasPublished(contents) && hasDraft(contents)) {
            throw conflict("文章仍有工作草稿，请先发布或丢弃草稿后删除");
        }
        for (var content : contents) {
            removeContent(content.getId());
        }
        documentMapper.deleteById(id);
    }

    private void removeContent(long contentId) {
        documentResourceMapper.delete(Wrappers.<DocumentResourceDO>lambdaQuery()
                .eq(DocumentResourceDO::getContentId, contentId));
        documentContentTagMapper.delete(Wrappers.<DocumentContentTagDO>lambdaQuery()
                .eq(DocumentContentTagDO::getContentId, contentId));
        documentContentMapper.deleteById(contentId);
        // 这里只解绑引用；不删除 Asset 或 OSS，对其他内容的共享引用没有影响。
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWrite(long id, String requestId, String requestHash) {
        documentMapper.update(null, Wrappers.<DocumentDO>lambdaUpdate()
                .eq(DocumentDO::getId, id)
                .setSql("revision = revision + 1")
                .set(DocumentDO::getLastRequestId, requestId)
                .set(DocumentDO::getLastRequestHash, requestHash)
                .set(DocumentDO::getUpdatedAt, now()));
    }

    private List<DocumentContentDO> slots(long id) {
        return documentContentMapper.selectList(Wrappers.<DocumentContentDO>lambdaQuery()
                .eq(DocumentContentDO::getDocumentId, id));
    }

    private DocumentContentDO slot(long id, ContentStage stage) {
        return documentContentMapper.selectOne(Wrappers.<DocumentContentDO>lambdaQuery()
                .eq(DocumentContentDO::getDocumentId, id).eq(DocumentContentDO::getStage, stage.name()));
    }

    private DocumentContentDO select(List<DocumentContentDO> slots, boolean manage) {
        if (manage) {
            var draft = slots.stream().filter(value -> ContentStage.DRAFT.name().equals(value.getStage())).findFirst();
            if (draft.isPresent()) {
                return draft.get();
            }
        }
        return slots.stream().filter(value -> ContentStage.PUBLISHED.name().equals(value.getStage()))
                .findFirst().orElse(null);
    }

    private boolean hasDraft(List<DocumentContentDO> slots) {
        return slots.stream().anyMatch(value -> ContentStage.DRAFT.name().equals(value.getStage()));
    }

    private boolean hasPublished(List<DocumentContentDO> slots) {
        return slots.stream().anyMatch(value -> ContentStage.PUBLISHED.name().equals(value.getStage()));
    }

    private DocumentDO required(long id) {
        var identity = documentMapper.selectById(id);
        if (identity == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "文档不存在");
        }
        return identity;
    }

    private ApiException conflict(String message) {
        return new ApiException(ApiErrorCode.DATA_CONFLICT, message);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
