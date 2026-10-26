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
import dev.amai.portfolio.system.api.TagKind;
import dev.amai.portfolio.web.exception.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档只持有两个当前内容槽位；所有写动作共用身份行锁和 revision。
 */
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

    /**
     * 列表只取用于卡片展示的标题和摘要。管理列表优先显示正在编辑的草稿，
     * 公开列表只显示正式正文，不能把尚未发布的标题、摘要或草稿状态展示给访客。
     */
    @Override
    @Transactional(readOnly = true, isolation = REPEATABLE_READ)
    public Map<Long, DocumentSummary> summaries(Set<Long> ids, boolean manage) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var identities = documentMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(DocumentDO::getId, value -> value));
        // 一次取得这一页文章的两类内容，但不加载可能很长的 Markdown 正文。
        var contents = documentContentMapper.selectList(Wrappers.<DocumentContentDO>lambdaQuery()
                .select(DocumentContentDO::getId, DocumentContentDO::getDocumentId,
                        DocumentContentDO::getStage, DocumentContentDO::getTitle,
                        DocumentContentDO::getSummary)
                .in(DocumentContentDO::getDocumentId, ids));
        var byDocument = contents.stream()
                .collect(Collectors.groupingBy(DocumentContentDO::getDocumentId));
        Map<Long, DocumentSummary> result = new HashMap<>();
        for (long id : ids) {
            var slots = byDocument.getOrDefault(id, List.of());
            var selected = select(slots, manage);
            var identity = identities.get(id);
            if (selected != null && identity != null) {
                result.put(id, new DocumentSummary(id, selected.getId(),
                        manage ? identity.getRevision() : null,
                        ContentStage.fromCode(selected.getStage()), manage && hasDraft(slots),
                        hasPublished(slots), selected.getTitle(), selected.getSummary()));
            }
        }
        return result;
    }

    /**
     * 读取同一份内容及其图片、附件和标签。管理端有草稿时读草稿，否则以正式正文进入编辑；
     * 公开阅读始终读正式正文，即使站长正在保存新的草稿。
     */
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
        return readContent(identity, content, hasDraft(slots), hasPublished(slots));
    }

    @Override
    @Transactional(readOnly = true, isolation = REPEATABLE_READ)
    public Document readPublished(DocumentDO identity, DocumentContentDO content) {
        // 公开文章主体已经按 PUBLISHED 过滤；只补充相同 contentId 的关联，避免正文和图片来自不同内容。
        // 公开投影不泄露草稿是否存在，因此无需再查询草稿槽位。
        return readContent(identity, content, false, true);
    }

    private Document readContent(DocumentDO identity, DocumentContentDO content,
            boolean hasDraft, boolean hasPublished) {
        var resources = documentResourceMapper.selectList(Wrappers.<DocumentResourceDO>lambdaQuery()
                .eq(DocumentResourceDO::getContentId, content.getId())
                .orderByAsc(DocumentResourceDO::getSortOrder, DocumentResourceDO::getId));
        var tags = documentContentTagMapper.selectList(Wrappers.<DocumentContentTagDO>lambdaQuery()
                        .eq(DocumentContentTagDO::getContentId, content.getId())
                        .orderByAsc(DocumentContentTagDO::getId))
                .stream()
                .map(tag -> new TagData(tag.getTagId(), tag.getName(), tag.getSlug(),
                        TagKind.fromCode(tag.getKind()).name(), null, null))
                .toList();
        return DocumentConvert.MAPPER.to(identity, content, hasDraft, hasPublished,
                DocumentConvert.MAPPER.to(resources), tags);
    }

    /**
     * 为再次保存准备可复用资源。先前已归档的图片和附件不应因为再次编辑而重新上传；
     * 同一 key 同时存在于正文和草稿时采用草稿记录，正文独有的引用仍保留供复用。
     */
    @Override
    @Transactional(readOnly = true, isolation = REPEATABLE_READ)
    public Map<String, DocumentResource> references(long id) {
        required(id);
        Map<String, DocumentResource> result = new HashMap<>();
        // 同一引用同时出现在正文和草稿时优先使用草稿；草稿移除但正文仍使用的文件也可以继续复用。
        var contents = slots(id);
        // 1. 找出当前草稿的内容身份，使草稿中的资源修改优先于正式正文。
        Set<Long> draftContentIds = contents.stream()
                .filter(value -> value.getStage() == ContentStage.DRAFT.code())
                .map(DocumentContentDO::getId)
                .collect(Collectors.toSet());

        List<Long> contentIds = contents.stream()
                .map(DocumentContentDO::getId)
                .toList();

        if (contentIds.isEmpty()) {
            return result;
        }
        var relations = documentResourceMapper.selectList(Wrappers.<DocumentResourceDO>lambdaQuery()
                .in(DocumentResourceDO::getContentId, contentIds));

        for (var relation : relations) {
            var resource = DocumentConvert.MAPPER.to(relation);
            // 2. 草稿行永远覆盖；正式正文行只在 key 尚未出现时填充。
            if (draftContentIds.contains(relation.getContentId())
                    || !result.containsKey(resource.key())) {
                result.put(resource.key(), resource);
            }
        }
        return result;
    }

    /** 新建请求响应丢失后，按发起账号和原请求标识找回文章，避免重试创建第二篇。 */
    @Override
    public DocumentDO findCreation(long owner, String requestId) {
        return documentMapper.selectOne(Wrappers.<DocumentDO>lambdaQuery()
                .eq(DocumentDO::getOwnerId, owner)
                .eq(DocumentDO::getCreateRequestId, requestId));
    }

    /** 首次保存只创建文章身份和一份工作草稿，不创建正式正文，也不使文章公开。 */
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

    /**
     * 正文保存只替换正文和对应资源关系，保留锁后读取的属性。
     * 没有工作草稿时先复制正式正文及关系，再修改草稿；公开正文始终不受本次编辑影响。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveContent(Document current, PreparedDocument prepared, long editor) {
        validateAssets(prepared);
        long contentId = ensureDraft(current, editor);
        documentContentMapper.update(null, Wrappers.<DocumentContentDO>lambdaUpdate()
                .eq(DocumentContentDO::getId, contentId)
                .set(DocumentContentDO::getContent, prepared.content())
                .set(DocumentContentDO::getReadingTimeMinutes, readingTime(prepared.content()))
                .set(DocumentContentDO::getUpdatedBy, editor)
                .set(DocumentContentDO::getUpdatedAt, now()));
        saveResources(contentId, prepared.resources());
    }

    /**
     * 属性保存只修改标题、摘要和标签。已有草稿不读取或校验 Asset，
     * 也不重新计算正文；复制正式正文建立草稿时仅复制原有关系，不重新上传文件。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveProperties(
            Document current, String title, String summary, List<TagData> tags, long editor) {
        long contentId = ensureDraft(current, editor);
        documentContentMapper.update(null, Wrappers.<DocumentContentDO>lambdaUpdate()
                .eq(DocumentContentDO::getId, contentId)
                .set(DocumentContentDO::getTitle, title)
                .set(DocumentContentDO::getSummary, summary)
                .set(DocumentContentDO::getUpdatedBy, editor)
                .set(DocumentContentDO::getUpdatedAt, now()));
        saveTags(contentId, tags);
    }

    /** 首次编辑已发布文章时复制当前正文，后续两类保存都复用同一草稿身份。 */
    private long ensureDraft(Document current, long editor) {
        if (current.hasDraft()) {
            return current.contentId();
        }
        // 来源行来自同一把文档锁内读取的正式正文；只改复制对象的身份，不能更新正式正文行。
        var draft = documentContentMapper.selectById(current.contentId());
        draft.setId(null);
        draft.setStage(ContentStage.DRAFT.code());
        draft.setUpdatedBy(editor);
        draft.setCreatedAt(now());
        draft.setUpdatedAt(draft.getCreatedAt());
        documentContentMapper.insert(draft);
        saveResources(draft.getId(), current.resources());
        saveTags(draft.getId(), current.tags());
        return draft.getId();
    }

    /**
     * 决定本次保存或状态操作能否继续。只有最近一次已成功请求可直接重试；
     * 中间已有其他操作时要求重新读取，不能将旧内容重新覆盖到当前文章。
     * 返回 false 代表已经成功执行过，本次只能读取结果，不能重复发布或重建草稿。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean beginWrite(long id, long revision, String requestId, String requestHash) {
        // 1. 保存和发布必须围绕同一篇文档依次完成，避免用旧草稿覆盖刚发布的正文；事务结束才释放锁。
        var identity = documentMapper.selectOne(Wrappers.<DocumentDO>lambdaQuery()
                .eq(DocumentDO::getId, id).last("FOR UPDATE"));
        if (identity == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "文档不存在");
        }
        // 2. 上次操作成功但响应丢失时，原请求只读取结果，不再修改草稿、发布正文或推进修订。
        // 此前准备阶段新上传但未绑定的文件仍由调用方清理，这里不承诺阻止准备阶段的上传。
        if (Objects.equals(identity.getLastRequestId(), requestId)) {
            if (!Objects.equals(identity.getLastRequestHash(), requestHash)) {
                // 修改后的内容是一次新保存，不能冒用已成功请求的标识，否则会把新内容误当作已经保存。
                throw new ApiException(ApiErrorCode.REQUEST_ID_CONFLICT, "请求标识已用于不同内容，请生成新的 requestId");
            }
            return false;
        }
        if (identity.getRevision() != revision) {
            // 其他保存或状态操作已经生效；请先重新读取并核对本地修改，不能直接覆盖当前文章。
            throw new ApiException(ApiErrorCode.DOCUMENT_REVISION_CONFLICT, "文档已被修改，请重新读取后操作");
        }
        return true;
    }

    private void persistDraft(DocumentDO identity, DocumentDraft draft) {
        validateAssets(draft.prepared());
        // 2. 重复保存原地更新唯一草稿；正式正文及其标签、资源关系不变。
        var existing = slot(identity.getId(), ContentStage.DRAFT);
        var content = DocumentConvert.MAPPER.to(draft);
        content.setDocumentId(identity.getId());
        content.setStage(ContentStage.DRAFT.code());
        content.setReadingTimeMinutes(readingTime(content.getContent()));
        content.setCreatedAt(existing == null ? now() : existing.getCreatedAt());
        content.setUpdatedAt(now());
        if (existing == null) {
            documentContentMapper.insert(content);
        } else {
            content.setId(existing.getId());
            documentContentMapper.updateById(content);
        }
        // 3. 没改动的图片附件继续复用，已从正文移除的引用只解除本内容关联；标签也只修改这一份草稿。
        saveResources(content.getId(), draft.prepared().resources());
        saveTags(content.getId(), draft.tags());
    }

    /** 新正文绑定的资源须就绪且用途匹配；属性保存不修改资源，所以不重复执行本校验。 */
    private void validateAssets(PreparedDocument prepared) {
        // 1. 确认正文引用的文件仍已归档，再建立关联；另一失败请求的清理不能误删本次准备使用的文件。
        var resourceIds = prepared.resources()
                .stream()
                .filter(value -> value.assetId() != null)
                .map(DocumentResource::assetId)
                .collect(Collectors.toSet());
        var assets = assetQueryService.lockReady(resourceIds);
        for (var resource : prepared.resources()) {
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
    }

    private short readingTime(String content) {
        int characters = content.codePointCount(0, content.length());
        return (short) Math.min(Short.MAX_VALUE,
                Math.max(1, (characters + READING_CHARACTERS_PER_MINUTE - 1)
                        / READING_CHARACTERS_PER_MINUTE));
    }

    private void saveResources(long contentId, List<DocumentResource> resources) {
        var old = documentResourceMapper.selectList(Wrappers.<DocumentResourceDO>lambdaQuery()
                        .eq(DocumentResourceDO::getContentId, contentId)).stream()
                .collect(Collectors.toMap(DocumentResourceDO::getKey, value -> value));
        List<DocumentResourceDO> additions = new ArrayList<>();
        List<DocumentResourceDO> updates = new ArrayList<>();
        int order = 0;
        for (var resource : resources) {
            var relation = DocumentConvert.MAPPER.to(resource, contentId, order++);
            var existing = old.remove(resource.key());
            if (existing == null) {
                additions.add(relation);
            } else if (!DocumentConvert.MAPPER.to(existing).equals(resource)
                    || !Objects.equals(existing.getSortOrder(), relation.getSortOrder())) {
                relation.setId(existing.getId());
                updates.add(relation);
            }
        }
        // 没变的引用不写库；新增和变更分别使用现有 MyBatis-Plus 批执行，仍随整篇草稿一起提交或回滚。
        if (!additions.isEmpty()) {
            documentResourceMapper.insert(additions);
        }
        if (!updates.isEmpty()) {
            documentResourceMapper.updateById(updates);
        }
        if (!old.isEmpty()) {
            documentResourceMapper.deleteByIds(old.values().stream()
                    .map(DocumentResourceDO::getId)
                    .toList());
        }
    }

    private void saveTags(long contentId, List<TagData> tags) {
        var old = documentContentTagMapper.selectList(Wrappers.<DocumentContentTagDO>lambdaQuery()
                        .eq(DocumentContentTagDO::getContentId, contentId)).stream()
                .collect(Collectors.toMap(DocumentContentTagDO::getTagId, value -> value));
        List<DocumentContentTagDO> additions = new ArrayList<>();
        List<DocumentContentTagDO> updates = new ArrayList<>();
        for (var tag : tags) {
            var existing = old.remove(tag.id());
            var relation = DocumentConvert.MAPPER.to(tag, contentId);
            if (existing == null) {
                additions.add(relation);
            } else {
                relation.setId(existing.getId());
                updates.add(relation);
            }
        }
        // 标签只改这一份草稿；批处理不改变正式正文的标签，也不在循环中逐项发起数据库调用。
        if (!additions.isEmpty()) {
            documentContentTagMapper.insert(additions);
        }
        if (!updates.isEmpty()) {
            documentContentTagMapper.updateById(updates);
        }
        if (!old.isEmpty()) {
            documentContentTagMapper.deleteByIds(old.values().stream()
                    .map(DocumentContentTagDO::getId)
                    .toList());
        }
    }

    /**
     * 将已保存草稿提升为唯一正式正文。旧正式正文及其关系被替换，
     * 草稿原有的图片、附件和标签随内容一起成为公开内容，发布后不再保留这份草稿。
     */
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
        draft.setStage(ContentStage.PUBLISHED.code());
        draft.setUpdatedAt(now());
        documentContentMapper.updateById(draft);
    }

    /** 放弃尚未发布的修改，保留正式正文；从未发布的新文章应直接整篇删除。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void discardDraft(long id) {
        var slots = slots(id);
        // 没有正式正文时不存在“放弃修改、回到正文”，应明确走整篇删除。
        if (!hasPublished(slots)) {
            throw conflict("这篇文章只有草稿，请使用整篇删除");
        }
        var draft = select(slots, true);
        if (draft == null || draft.getStage() != ContentStage.DRAFT.code()) {
            throw conflict("没有可丢弃的草稿");
        }
        removeContent(draft.getId());
    }

    /**
     * 删除整篇文章的文档内容。是否已下架由博客服务先判断；
     * 这里先确认正式正文是否仍有工作草稿，有则拒绝，提示用户先处理草稿，避免误删未发布修改。
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void delete(long id) {
        var contents = slots(id);
        // 先查有没有未处理草稿；拒绝时尚未删除任何正文或引用。
        if (hasPublished(contents) && hasDraft(contents)) {
            throw conflict("文章仍有工作草稿，请先发布或丢弃草稿后删除");
        }
        // 只有草稿的新文章，或已下架且没有草稿的正文，才会走到实际删除。
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

    /** 记录最近完成的操作并推进写修订；它用于防止迟到请求覆盖当前内容，不保存历史正文。 */
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
                .eq(DocumentContentDO::getDocumentId, id).eq(DocumentContentDO::getStage, stage.code()));
    }

    private DocumentContentDO select(List<DocumentContentDO> slots, boolean manage) {
        // 管理端优先继续编辑未发布的修改；不创建新的草稿副本。
        if (manage) {
            var draft = slots.stream()
                    .filter(value -> value.getStage() == ContentStage.DRAFT.code())
                    .findFirst();
            if (draft.isPresent()) {
                return draft.get();
            }
        }
        // 公开阅读，以及没有草稿的管理阅读，都只返回当前正式正文。
        return slots.stream()
                .filter(value -> value.getStage() == ContentStage.PUBLISHED.code())
                .findFirst()
                .orElse(null);
    }

    private boolean hasDraft(List<DocumentContentDO> slots) {
        return slots.stream()
                .anyMatch(value -> value.getStage() == ContentStage.DRAFT.code());
    }

    private boolean hasPublished(List<DocumentContentDO> slots) {
        return slots.stream()
                .anyMatch(value -> value.getStage() == ContentStage.PUBLISHED.code());
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
