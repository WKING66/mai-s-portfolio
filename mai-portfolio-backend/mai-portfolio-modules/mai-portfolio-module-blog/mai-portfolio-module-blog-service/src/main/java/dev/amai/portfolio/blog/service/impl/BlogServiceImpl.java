package dev.amai.portfolio.blog.service.impl;

import static dev.amai.portfolio.blog.constant.BlogPolicy.*;
import static dev.amai.portfolio.blog.constant.DocumentPolicy.MAX_SOURCE_FILENAME_LENGTH;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.blog.component.*;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.convert.*;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.request.*;
import dev.amai.portfolio.blog.entity.vo.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.blog.mapper.BlogPostMapper;
import dev.amai.portfolio.blog.service.*;
import dev.amai.portfolio.common.PageRequest;
import dev.amai.portfolio.common.ViewPageVO;
import dev.amai.portfolio.security.context.AccountContext;
import dev.amai.portfolio.system.api.*;
import dev.amai.portfolio.web.exception.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import java.util.HexFormat;
import java.util.stream.Collectors;
import org.slf4j.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/** 博客权限、公开状态与流程编排；内容、标签和资源关系由 Document 统一持久化。 */
@Service
public class BlogServiceImpl implements BlogService {
    private static final Logger LOG = LoggerFactory.getLogger(BlogServiceImpl.class);

    private final BlogPostMapper blogPostMapper;

    private final DocumentService documentService;

    private final DocumentImportTaskService documentImportTaskService;

    private final DocumentResourceResolver documentResourceResolver;

    private final DocumentParser documentParser;

    private final DocumentRenderer documentRenderer;

    private final AssetFileService assetFileService;

    private final AssetQueryService assetQueryService;

    private final TaxonomyQueryService taxonomyQueryService;

    private final DocumentProperties documentProperties;

    private final TransactionTemplate transactionTemplate;

    private record SavedDraft(
            BlogPostDO blog,

            Document document) {}

    public BlogServiceImpl(BlogPostMapper blogPostMapper, DocumentService documentService,
            DocumentImportTaskService documentImportTaskService,
            DocumentResourceResolver documentResourceResolver, DocumentParser documentParser,
            DocumentRenderer documentRenderer, AssetFileService assetFileService,
            AssetQueryService assetQueryService, TaxonomyQueryService taxonomyQueryService,
            DocumentProperties documentProperties,
            PlatformTransactionManager transactionManager) {
        this.blogPostMapper = blogPostMapper;
        this.documentService = documentService;
        this.documentImportTaskService = documentImportTaskService;
        this.documentResourceResolver = documentResourceResolver;
        this.documentParser = documentParser;
        this.documentRenderer = documentRenderer;
        this.assetFileService = assetFileService;
        this.assetQueryService = assetQueryService;
        this.taxonomyQueryService = taxonomyQueryService;
        this.documentProperties = documentProperties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 公开列表只展示已上架正文；管理列表优先展示草稿，方便站长继续编辑。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ViewPageVO<BlogCardVo> list(String view, PageRequest pagination) {
        boolean manage = manage(view);
        int page = pagination.getPage();
        int size = pagination.sizeOrDefault();
        var query = Wrappers.<BlogPostDO>lambdaQuery();
        if (!manage) {
            query.eq(BlogPostDO::getStatus, PUBLISHED);
        }
        query.orderByDesc(BlogPostDO::getPublishedAt).orderByDesc(BlogPostDO::getId);
        var result = blogPostMapper.selectPage(new Page<BlogPostDO>(page, size), query);
        var summaries = documentService.summaries(result.getRecords().stream()
                .map(BlogPostDO::getDocumentId)
                .collect(Collectors.toSet()), manage);
        var cards = result.getRecords().stream()
                .map(blog -> {
                    var summary = summaries.get(blog.getDocumentId());
                    if (summary == null) {
                        throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "文章内容缺失");
                    }
                    return BlogConvert.MAPPER.to(blog, summary, state(blog),
                            manage ? summary.hasDraft() : null);
                })
                .toList();
        return new ViewPageVO<>(manage ? MANAGE_VIEW : PUBLIC_VIEW, page, size, result.getTotal(), cards);
    }

    /** 读取当前编辑内容；没有草稿时以正式正文初始化编辑，不因打开页面就创建一份草稿。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlogVo get(long id) {
        var blog = required(id);
        return project(blog, documentService.read(blog.getDocumentId(), true), true);
    }

    /** 访客只能按固定 slug 读取上架正文；下架后该入口不再提供文章，后台草稿从不在这里展示。 */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlogVo publicArticle(String slug) {
        // 先在三表关联中定位上架的正式正文；不能为了少查一次而取草稿，也不把长正文与每张图片重复 JOIN。
        var article = blogPostMapper.selectPublishedArticle(slug, PUBLISHED, ContentStage.PUBLISHED.code());
        if (article == null) {
            // 下架、没有正式正文或 slug 不存在均不提供公开文章；原有 OSS 地址不由这里撤销。
            throw missing();
        }
        var document = documentService.readPublished(article.getDocument(), article.getContent());
        return project(article.getBlog(), document, false);
    }

    /**
     * 新建和编辑都只保存草稿。已公开的文章继续展示旧正文，
     * 只有显式发布才替换正文；下载开关是博客级设置，保存后立即生效。
     */
    @Override
    public BlogVo save(Long id, BlogRequest request) {
        // 1. 先识别响应丢失后的重试：已保存过的同一次请求直接读取结果，避免再上传一遍资源。
        long account = AccountContext.currentAccount().id();
        String hash = writeFingerprint(request);
        BlogPostDO existing = id == null ? null : required(id);
        var creation = id == null ? documentService.findCreation(account, request.requestId()) : null;
        if (creation != null) {
            requireSameHash(creation.getCreateRequestHash(), hash);
            return get(blogByDocument(creation.getId()).getId());
        }
        // 这里先确认客户端看到的修订是否过期；真正执行写入前还会在同一文档上再次确认，防止准备资源期间发生其他操作。
        if (existing != null) {
            var current = documentService.read(existing.getDocumentId(), true);
            if (current.revision() != request.revision()) {
                return savedRetry(existing, request, hash);
            }
        }
        var tags = taxonomyQueryService.requireTags(request.tagIds());
        var previous = existing == null ? Map.<String, DocumentResource>of()
                : documentService.references(existing.getDocumentId());
        // 2. 把新图片/附件归档为稳定引用，已有引用优先复用。资源未就绪可留在草稿，但不能据此发布。
        var prepared = documentResourceResolver.prepare(request.content(), previous, Map.of(),
                request.remoteRightsConfirmed(), account);
        var draft = new DocumentDraft(request.title(), request.summary(), prepared, tags,
                account, request.requestId(), hash);
        // 3. 一起保存草稿、标签与下载设置。若保存失败，只清理这次新上传且未被其他内容引用的资源，旧资源保持不变。
        var saved = persist(existing, request, draft, DocumentOrigin.EDITOR.code(), null, null);
        return project(saved.blog(), saved.document(), true);
    }

    /** 修订已推进时，仅允许最后一次原请求读取成功结果；其他旧请求要求重新读取，不能重新写回旧草稿。 */
    private BlogVo savedRetry(BlogPostDO existing, BlogRequest request, String hash) {
        transactionTemplate.executeWithoutResult(status -> {
            if (documentService.beginWrite(existing.getDocumentId(),
                    request.revision(), request.requestId(), hash)) {
                throw new ApiException(ApiErrorCode.DATA_CONFLICT, "文档已被修改，请重新读取后保存");
            }
        });
        return get(existing.getId());
    }

    /**
     * 将 Markdown 和用户选中的本地资源导入为一篇草稿，不能导入即发布。
     * 单个图片或附件缺失时保存正文及具体问题，用户修正后再发布；整篇失败也保留来源记录。
     */
    @Override
    public BlogVo importDocument(MultipartFile file, List<MultipartFile> input,
            boolean rights, List<Long> tagIds, String requestId) {
        long account = AccountContext.currentAccount().id();
        // 1. 来源记录独立提交；解析或正文事务失败仍有可追溯入口。
        String filename = sourceFilename(file == null ? null : file.getOriginalFilename());
        var task = documentImportTaskService.begin(account, requestId, filename,
                file == null ? 0 : file.getSize());
        SavedDraft saved;
        try {
            byte[] source = read(file, documentProperties.getMaxContentBytes());
            String sourceSha256 = sha256(source);
            documentImportTaskService.recordSource(task.getId(), sourceSha256);
            var tags = taxonomyQueryService.requireTags(tagIds);
            var local = localResources(input);
            var parsed = documentParser.parse(filename, source, local);
            // 2. 来源差异止于 Parser；资源归档和保存复用编辑链路。
            var prepared = documentResourceResolver.prepare(parsed.content(), Map.of(),
                    parsed.resources(), rights, account);
            var request = new BlogRequest(null, requestId, "",
                    StrUtil.subPre(parsed.title(), MAX_TITLE_LENGTH), "",
                    prepared.content(), tagIds, true, rights);
            var draft = new DocumentDraft(request.title(), request.summary(), prepared, tags,
                    account, requestId, writeFingerprint(request));
            // 3. 博客、内容、关系、导入成功及资源问题清单共同提交。
            saved = persist(null, request, draft, parsed.origin().code(), task.getId(), sourceSha256);
        } catch (RuntimeException failure) {
            try {
                documentImportTaskService.failed(task.getId(), failure);
            } catch (RuntimeException traceFailure) {
                LOG.error("Import failure trace unavailable, taskId={}, failureType={}",
                        task.getId(), traceFailure.getClass().getSimpleName());
                failure.addSuppressed(traceFailure);
            }
            throw failure;
        }
        // 预览签名在提交后生成，不把已成功的导入误报成失败。
        return project(saved.blog(), saved.document(), true);
    }

    /** 按 Markdown 相对路径匹配上传文件，保留目录层级区分同名文件；不读取服务器上的同名路径。 */
    private Map<String, DocumentInputResource> localResources(List<MultipartFile> input) {
        if (input.size() > documentProperties.getMaxResources()) {
            throw invalid("本地资源数量过多");
        }
        Map<String, DocumentInputResource> local = new HashMap<>();
        long total = 0;
        for (var resource : input) {
            String path = DocumentResourceResolver.localPath(resource.getOriginalFilename());
            if (local.containsKey(path)) {
                throw invalid("本地资源路径重复");
            }
            total += resource.getSize();
            if (total > documentProperties.getMaxImportBytes()) {
                throw invalid("导入资源总大小超过限制");
            }
            local.put(path, new DocumentInputResource(path, resource.getContentType(),
                    read(resource, documentProperties.getMaxImportBytes())));
        }
        return local;
    }

    /** 将内容、标签和博客设置作为一次保存；任何一步失败都不能留下半篇新文章。 */
    private SavedDraft persist(BlogPostDO existing, BlogRequest request, DocumentDraft draft,
            int origin, Long importTaskId, String sourceSha256) {
        try {
            SavedDraft saved = transactionTemplate.execute(status -> {
                BlogPostDO blog = existing == null ? new BlogPostDO() : required(existing.getId());
                if (existing != null) {
                    boolean apply = documentService.beginWrite(blog.getDocumentId(),
                            request.revision(), request.requestId(), draft.requestHash());
                    // 锁后重读，不能用锁前旧对象覆盖另一写操作。
                    blog = required(existing.getId());
                    var current = documentService.read(blog.getDocumentId(), true);
                    if (!apply) {
                        return new SavedDraft(blog, current);
                    }
                    if (current.hasPublished() && !Objects.equals(blog.getSlug(), cleanSlug(request.slug()))) {
                        throw invalid("首次发布后文章标识不可修改");
                    }
                }
                Document document = existing == null
                        ? documentService.create(draft.editor(), origin, draft)
                        : documentService.save(blog.getDocumentId(), draft);
                blog.setSlug(cleanSlug(request.slug()));
                blog.setDocumentId(document.id());
                blog.setAllowVisitorDownload(request.allowDownload()
                        ? VISITOR_DOWNLOAD_ENABLED : VISITOR_DOWNLOAD_DISABLED);
                blog.setUpdatedAt(now());
                if (existing == null) {
                    blog.setStatus(DRAFT);
                    blog.setCreatedAt(blog.getUpdatedAt());
                    blogPostMapper.insert(blog);
                } else {
                    blogPostMapper.updateById(blog);
                }
                if (importTaskId != null) {
                    documentImportTaskService.succeeded(importTaskId, document.id(), sourceSha256);
                }
                return new SavedDraft(blog, document);
            });
            // 同请求并发准备可能额外上传：只回收提交结果没有使用的本次新增资产。
            var unusedNewAssets = new HashSet<>(draft.prepared().newlyCreatedAssets());
            saved.document().resources().forEach(resource -> unusedNewAssets.remove(resource.assetId()));
            if (!unusedNewAssets.isEmpty()) {
                assetFileService.discardUnbound(unusedNewAssets, draft.editor());
            }
            return saved;
        } catch (DuplicateKeyException failure) {
            assetFileService.discardUnbound(draft.prepared().newlyCreatedAssets(), draft.editor());
            if (existing == null && importTaskId == null) {
                var creation = documentService.findCreation(draft.editor(), draft.requestId());
                if (creation != null) {
                    requireSameHash(creation.getCreateRequestHash(), draft.requestHash());
                    return new SavedDraft(blogByDocument(creation.getId()), documentService.read(creation.getId(), true));
                }
            }
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "文章标识或请求标识已被使用，请重新读取");
        } catch (RuntimeException failure) {
            assetFileService.discardUnbound(draft.prepared().newlyCreatedAssets(), draft.editor());
            throw failure;
        }
    }

    /** 发布草稿、下架正文、恢复正文和丢弃草稿是四种不同业务动作，不能相互代替。 */
    @Override
    public BlogVo transition(long id, BlogRevisionRequest request, BlogAction action) {
        var initial = required(id);
        String actionHash = sha256(action.name().getBytes(StandardCharsets.UTF_8));
        transactionTemplate.executeWithoutResult(status -> {
            // 1. 同一身份锁和修订保护所有状态动作，重试不重复改变正文。
            if (!documentService.beginWrite(initial.getDocumentId(), request.revision(),
                    request.requestId(), actionHash)) {
                return;
            }
            var blog = required(id);
            var current = documentService.read(blog.getDocumentId(), true);
            // 2. 发布提升草稿；恢复只开放正式正文；下架不删除内容。
            switch (action) {
                case PUBLISH -> {
                    // 发布只接受已经保存的草稿，不能把客户端尚未保存的编辑或旧正式正文当作新发布内容。
                    if (!current.hasDraft()) {
                        throw invalid("没有已保存的草稿；重新上架请使用恢复操作");
                    }
                    validatePublish(blog, current);
                    documentService.publish(blog.getDocumentId());
                    blog.setStatus(PUBLISHED);
                    blog.setPublishedAt(now());
                }
                case UNPUBLISH -> {
                    // 下架只关闭公开入口，正文和正在编辑的草稿都保留，已签发的静态资源地址不要求立即失效。
                    if (blog.getStatus() != PUBLISHED) {
                        throw invalid("文章当前未上架");
                    }
                    blog.setStatus(DRAFT);
                }
                case RESTORE -> {
                    // 恢复的是最近正式正文：即使同时有草稿，也不能把未发布修改提前展示给访客。
                    if (blog.getStatus() == PUBLISHED || !current.hasPublished()) {
                        throw invalid("只有已下架且存在正式正文的文章才能恢复");
                    }
                    validatePublish(blog, documentService.read(blog.getDocumentId(), false));
                    blog.setStatus(PUBLISHED);
                }
                case DISCARD_DRAFT -> {
                    // 只丢弃正在编辑的副本；正式正文及其上架状态保持原样。
                    documentService.discardDraft(blog.getDocumentId());
                }
            }
            blog.setUpdatedAt(now());
            blogPostMapper.updateById(blog);
            documentService.recordWrite(blog.getDocumentId(), request.requestId(),
                    actionHash);
        });
        return get(id);
    }

    /** 发布/恢复前检查内容已完整保存、所有图片附件已归档，避免访客看到缺正文或坏引用的文章。 */
    private void validatePublish(BlogPostDO blog, Document document) {
        if (blog.getSlug() == null || document.title() == null
                || document.title().isBlank() || document.content().isBlank()) {
            throw invalid("发布需要已保存的文章标识、标题和正文");
        }
        if (document.resources().stream()
                .anyMatch(value -> value.status() != ResourceStatus.READY)) {
            throw invalid("请先解决全部图片和附件问题");
        }
        // 写事务只校验/锁定元数据，不访问 OSS 签名或字节。
        assetQueryService.lockReady(document.resources().stream()
                .map(DocumentResource::assetId)
                .collect(Collectors.toSet()));
    }

    /**
     * 整篇删除只允许未公开的文章。已有正式正文时还需先处理草稿，
     * 不能因为想清理文章就连同未发布修改一起删除；共享图片和附件不随文章物理删除。
     */
    @Override
    public void delete(long id, BlogRevisionRequest request) {
        var initial = required(id);
        transactionTemplate.executeWithoutResult(status -> {
            documentService.beginWrite(initial.getDocumentId(), request.revision(),
                    request.requestId(), sha256("DELETE".getBytes(StandardCharsets.UTF_8)));
            var blog = required(id);
            // 先要求下架；拒绝时没有触碰正文、草稿或图片附件引用。
            if (blog.getStatus() == PUBLISHED) {
                throw invalid("已上架文章不得删除，请先下架");
            }
            // 再检查正文是否还有草稿：有则提示先发布或丢弃，全部检查通过后才删除正文和引用；导入记录仍保留。
            documentService.delete(blog.getDocumentId());
            blogPostMapper.deleteById(id);
        });
    }

    /**
     * 管理导出读取站长当前编辑内容；公开导出只能读取已上架且允许下载的正式正文。
     * 此处选定导出内容，文件生成交给 ExportService；普通图片仍由浏览器访问 OSS 地址。
     */
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Document exportDocument(long id, String view) {
        boolean manage = manage(view);
        var blog = required(id);
        if (!manage && (blog.getStatus() != PUBLISHED
                || blog.getAllowVisitorDownload() != VISITOR_DOWNLOAD_ENABLED)) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, "该文章当前不允许下载");
        }
        return documentService.read(blog.getDocumentId(), manage);
    }

    /** 组装页面所需资源地址；生成预览地址失败不应把已经保存成功的草稿误报为保存失败。 */
    private BlogVo project(BlogPostDO blog, Document document, boolean manage) {
        List<DocumentResourceVo> resources;
        String previewIssue = null;
        try {
            resources = documentRenderer.resources(document, manage);
        } catch (ApiException failure) {
            if (!manage || !failure.code().status().is5xxServerError()) {
                throw failure;
            }
            LOG.warn("Blog preview unavailable, blogId={}, contentId={}", blog.getId(), document.contentId());
            previewIssue = "正文已保存，资源预览暂时不可用，请稍后重新读取。";
            resources = document.resources().stream()
                    .map(resource -> DocumentConvert.MAPPER.to(
                    resource, null, resource.status(), resource.originalUri(), resource.errorMessage()))
                    .toList();
        }
        boolean resourcesReady = previewIssue == null
                && resources.stream()
                        .allMatch(resource -> resource.status() == ResourceStatus.READY);
        return BlogConvert.MAPPER.to(blog, document, state(blog),
                manage ? document.revision() : null, manage ? document.hasDraft() : null,
                resourcesReady, blog.getAllowVisitorDownload() == VISITOR_DOWNLOAD_ENABLED,
                previewIssue, resources);
    }

    private BlogPostDO required(long id) {
        var blog = blogPostMapper.selectById(id);
        if (blog == null) {
            throw missing();
        }
        return blog;
    }

    private BlogPostDO blogByDocument(long documentId) {
        var blog = blogPostMapper.selectOne(Wrappers.<BlogPostDO>lambdaQuery()
                .eq(BlogPostDO::getDocumentId, documentId));
        if (blog == null) {
            throw missing();
        }
        return blog;
    }

    private byte[] read(MultipartFile file, int max) {
        if (file == null || file.isEmpty() || file.getSize() > max) {
            throw invalid("导入文件为空或超过大小限制");
        }
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(max + 1);
            if (bytes.length > max) {
                throw invalid("导入文件超过大小限制");
            }
            return bytes;
        } catch (IOException failure) {
            throw invalid("导入文件读取失败");
        }
    }

    private String sourceFilename(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.length() > MAX_SOURCE_FILENAME_LENGTH || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("导入文件名不合法");
        }
        return name;
    }

    private boolean manage(String view) {
        if (MANAGE_VIEW.equals(view)) {
            return true;
        }
        if (PUBLIC_VIEW.equals(view)) {
            return false;
        }
        throw invalid("列表视图无效");
    }

    private String cleanSlug(String slug) {
        return slug == null || slug.isBlank() ? null : slug;
    }

    private void requireSameHash(String actual, String expected) {
        if (!Objects.equals(actual, expected)) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "请求标识已用于不同内容，请生成新的 requestId");
        }
    }

    /**
     * 识别“同一请求标识却换了输入”的情况。这里只比较写入意图，
     * 不把原始文件摘要当成请求指纹；旧请求被后续写入超越后仍按 revision 返回冲突。
     */
    private String writeFingerprint(BlogRequest request) {
        var digest = sha256Digest();
        addFingerprintField(digest, "SAVE");
        addFingerprintField(digest, request.slug());
        addFingerprintField(digest, request.title());
        addFingerprintField(digest, request.summary());
        addFingerprintField(digest, request.content());
        addFingerprintField(digest, Boolean.toString(request.allowDownload()));
        addFingerprintField(digest, Boolean.toString(request.remoteRightsConfirmed()));
        request.tagIds().forEach(id -> addFingerprintField(digest, id.toString()));
        return HexFormat.of().formatHex(digest.digest());
    }

    private String sha256(byte[] input) {
        return HexFormat.of().formatHex(sha256Digest().digest(input));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JVM must support SHA-256", failure);
        }
    }

    private void addFingerprintField(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        // 长度参与摘要：字段边界不同、null 与空字符串都不能被当成相同请求。
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes == null ? -1 : bytes.length)
                .array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    private String state(BlogPostDO blog) {
        return blog.getStatus() == PUBLISHED ? "PUBLISHED" : "DRAFT";
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private ApiException missing() {
        return new ApiException(ApiErrorCode.NOT_FOUND, "文章不存在或未公开");
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
