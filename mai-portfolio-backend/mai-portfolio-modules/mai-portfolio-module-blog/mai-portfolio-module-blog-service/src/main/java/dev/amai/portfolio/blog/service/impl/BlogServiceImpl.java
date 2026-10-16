package dev.amai.portfolio.blog.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.blog.component.*;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.convert.BlogConvert;
import dev.amai.portfolio.blog.convert.DocumentConvert;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.request.BlogRequest;
import dev.amai.portfolio.blog.entity.vo.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.blog.mapper.BlogPostMapper;
import dev.amai.portfolio.blog.service.*;
import dev.amai.portfolio.system.api.*;
import dev.amai.portfolio.web.exception.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import static dev.amai.portfolio.blog.constant.BlogPolicy.*;

/** 博客发布/权限与文档生命周期分别承载业务事实；外部文件 IO 不占用数据库事务。 */
@Service
public class BlogServiceImpl implements BlogService {
    private static final Logger LOG =
            LoggerFactory.getLogger(BlogServiceImpl.class);
    private final BlogPostMapper blogPostMapper;
    private final DocumentService documentService;
    private final DocumentResourceResolver documentResourceResolver;
    private final DocumentParser documentParser;
    private final DocumentRenderer documentRenderer;
    private final AssetFileService assetFileService;
    private final OwnerAuthorizationService ownerAuthorizationService;
    private final AccountIdentityService accountIdentityService;
    private final DocumentProperties documentProperties;
    private final TransactionTemplate transactionTemplate;

    private record SavedDraft(
            BlogPostDO blog,

            Document document) {}

    public BlogServiceImpl(
            BlogPostMapper blogPostMapper,
            DocumentService documentService,
            DocumentResourceResolver documentResourceResolver,
            DocumentParser documentParser,
            DocumentRenderer documentRenderer,
            AssetFileService assetFileService,
            OwnerAuthorizationService ownerAuthorizationService,
            AccountIdentityService accountIdentityService,
            DocumentProperties documentProperties,
            PlatformTransactionManager transactionManager) {
        this.blogPostMapper = blogPostMapper;
        this.documentService = documentService;
        this.documentResourceResolver = documentResourceResolver;
        this.documentParser = documentParser;
        this.documentRenderer = documentRenderer;
        this.assetFileService = assetFileService;
        this.ownerAuthorizationService = ownerAuthorizationService;
        this.accountIdentityService = accountIdentityService;
        this.documentProperties = documentProperties;
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public BlogPageVo list(String view, int page, int size) {
        boolean manage = manage(view);
        if (manage) {
            ownerAuthorizationService.requireOwner();
        }
        var query = Wrappers.<BlogPostDO>lambdaQuery();
        if (!manage) {
            query.eq(BlogPostDO::getStatus, PUBLISHED);
        }
        query.orderByDesc(BlogPostDO::getPublishedAt).orderByDesc(BlogPostDO::getId);
        var blogPage = blogPostMapper.selectPage(new Page<BlogPostDO>(page, size), query);
        // 公开列表只投影已发布版本；管理列表读取最新草稿，两者共用文档查询能力。
        Map<Long, Long> versionIdsByDocument = new HashMap<>();
        blogPage.getRecords()
                .forEach(
                        blogPost ->
                                versionIdsByDocument.put(
                                        blogPost.getDocumentId(),
                                        manage ? null : blogPost.getPublishedVersionId()));
        var documentSummaries = documentService.summaries(versionIdsByDocument);
        var blogCards =
                blogPage.getRecords().stream()
                        .map(
                                blogPost -> {
                                    var documentSummary =
                                            documentSummaries.get(blogPost.getDocumentId());
                                    if (documentSummary == null) {
                                        throw new ApiException(
                                                ApiErrorCode.INTERNAL_ERROR, "文章文档版本缺失");
                                    }
                                    return BlogConvert.MAPPER.to(
                                            blogPost, documentSummary, state(blogPost));
                                })
                        .toList();
        return new BlogPageVo(
                manage ? MANAGE_VIEW : PUBLIC_VIEW, page, size, blogPage.getTotal(), blogCards);
    }

    @Override
    public BlogVo get(long id) {
        ownerAuthorizationService.requireOwner();
        var blog = required(id);
        return project(blog, documentService.read(blog.getDocumentId(), null), true);
    }

    @Override
    public BlogVo publicArticle(String slug) {
        var blog =
                blogPostMapper.selectOne(
                        Wrappers.<BlogPostDO>lambdaQuery()
                                .eq(BlogPostDO::getSlug, slug)
                                .eq(BlogPostDO::getStatus, PUBLISHED));
        if (blog == null) {
            throw missing();
        }
        return project(
                blog,
                documentService.read(blog.getDocumentId(), blog.getPublishedVersionId()),
                false);
    }

    @Override
    public BlogVo save(Long id, BlogRequest request) {
        // 1. 请求格式已在 HTTP 边界校验；这里校验管理权限及旧版本。提交时 Document 再做 CAS，避免预检查到提交之间丢失更新。
        ownerAuthorizationService.requireOwner();
        long accountId = accountIdentityService.currentAccountId();
        BlogPostDO existing = id == null ? null : required(id);
        Map<String, DocumentResource> previous = new HashMap<>();
        if (existing != null) {
            if (documentService.lockVersion(existing.getDocumentId()) != request.version()) {
                throw conflict();
            }
            if (existing.getPublishedVersionId() != null
                    && !Objects.equals(existing.getSlug(), request.slug())) {
                throw invalid("首次发布后文章标识不可修改");
            }
            documentService
                    .read(existing.getDocumentId(), null)
                    .resources()
                    .forEach(r -> previous.put(r.key(), r));
        }
        // 2. 使用旧版本引用及本次授权准备资源；统一结果不依赖原始文件格式。
        var prepared =
                documentResourceResolver.prepare(
                        request.content(),
                        previous,
                        Map.of(),
                        request.remoteRightsConfirmed(),
                        accountId);
        // 3. 短事务提交正文和资源关系；失败仅补偿本次新建的未绑定资产。
        return persist(existing, request, prepared, DocumentOrigin.EDITOR.code(), accountId);
    }

    @Override
    public BlogVo importDocument(MultipartFile file, List<MultipartFile> input, boolean rights) {
        // 1. 校验 OWNER、输入资源数量和总量；上传的资源文件名是正文相对路径。总量上限指本地资源，正文另受 maxContentBytes 约束。
        ownerAuthorizationService.requireOwner();
        long accountId = accountIdentityService.currentAccountId();
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
            local.put(
                    path,
                    new DocumentInputResource(
                            path,
                            resource.getContentType(),
                            read(resource, documentProperties.getMaxImportBytes())));
        }
        // 2. 格式差异止于 Parser；资源归档、Document 保存和 Blog 创建不依赖原始格式。
        var parsed =
                documentParser.parse(
                        file == null ? null : file.getOriginalFilename(),
                        read(file, documentProperties.getMaxContentBytes()),
                        local);
        // 3. 统一归档导入资源；按草稿字段上限截断标题，正文以规范化 Document 为准。
        var prepared =
                documentResourceResolver.prepare(
                        parsed.content(), Map.of(), parsed.resources(), rights, accountId);
        // 导入草稿的缺省值属于创建流程，不作为通用对象转换规则。
        var request = new BlogRequest(
                null,
                "",
                StrUtil.subPre(parsed.title(), MAX_TITLE_LENGTH),
                "",
                prepared.content(),
                true,
                rights);
        return persist(null, request, prepared, parsed.origin().code(), accountId);
    }

    private BlogVo persist(
            BlogPostDO existing,
            BlogRequest request,
            PreparedDocument prepared,
            int origin,
            long accountId) {
        SavedDraft saved;
        try {
            // 原子提交文档版本、资源外键和博客关联；OSS 上传不放进数据库事务。
            saved =
                    transactionTemplate.execute(
                            status -> {
                                var blogPost = existing == null ? new BlogPostDO() : existing;
                                Document document =
                                        existing == null
                                                ? documentService.create(
                                                        accountId,
                                                        origin,
                                                        request.title(),
                                                        request.summary(),
                                                        prepared)
                                                : documentService.append(
                                                        blogPost.getDocumentId(),
                                                        request.version(),
                                                        accountId,
                                                        request.title(),
                                                        request.summary(),
                                                        prepared);
                                blogPost.setSlug(
                                        request.slug() == null || request.slug().isBlank()
                                                ? null
                                                : request.slug());
                                blogPost.setDocumentId(document.id());
                                blogPost.setAllowVisitorDownload(
                                        request.allowDownload()
                                                ? VISITOR_DOWNLOAD_ENABLED
                                                : VISITOR_DOWNLOAD_DISABLED);
                                blogPost.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                                if (existing == null) {
                                    blogPost.setStatus(DRAFT);
                                    blogPost.setCreatedAt(blogPost.getUpdatedAt());
                                    blogPostMapper.insert(blogPost);
                                } else {
                                    blogPostMapper.updateById(blogPost);
                                }
                                return new SavedDraft(blogPost, document);
                            });
        } catch (RuntimeException failure) {
            // 回滚后只回收本次新建且没有绑定的 Asset；存储删除失败由 Asset 层记录。
            assetFileService.discardUnbound(prepared.newlyCreatedAssets(), accountId);
            throw failure;
        }
        LOG.info(
                "Blog draft saved, blogId={}, documentId={}",
                saved.blog().getId(),
                saved.document().id());
        return project(saved.blog(), saved.document(), true);
    }

    @Override
    public BlogVo publish(long id, long version, boolean published) {
        // 1. 验证 OWNER 并读取文章；发布固定公开版本指针；下架仅改变可见状态，保留文档历史及普通资源地址。
        ownerAuthorizationService.requireOwner();
        var blogPost = required(id);
        // 2. CAS 与发布指针更新处于同一事务；发布前验证正文及所有资源可用性。
        transactionTemplate.executeWithoutResult(
                status -> {
                    documentService.advanceLock(blogPost.getDocumentId(), version);
                    var document = documentService.read(blogPost.getDocumentId(), null);
                    if (published) {
                        if (blogPost.getSlug() == null
                                || document.title() == null
                                || document.title().isBlank()
                                || document.content().isBlank()) {
                            throw invalid("发布需要文章标识、标题和正文");
                        }
                        if (document.resources().stream()
                                .anyMatch(r -> r.status() != ResourceStatus.READY)) {
                            throw invalid("请先解决全部图片和附件问题");
                        }
                        if (documentRenderer.resources(document, true).stream()
                                .anyMatch(r -> r.status() != ResourceStatus.READY)) {
                            throw invalid("引用资源当前不可用");
                        }
                        blogPost.setPublishedVersionId(document.versionId());
                        blogPost.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
                    }
                    blogPost.setStatus(published ? PUBLISHED : DRAFT);
                    blogPost.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    blogPostMapper.updateById(blogPost);
                });
        LOG.info("Blog publication changed, blogId={}, published={}", id, published);
        return get(id);
    }

    @Override
    public Document exportDocument(long id, String view) {
        accountIdentityService.currentAccountId();
        boolean manage = manage(view);
        if (manage) {
            ownerAuthorizationService.requireOwner();
        }
        var blogPost = required(id);
        if (!manage
                && (blogPost.getStatus() != PUBLISHED
                        || blogPost.getAllowVisitorDownload() != VISITOR_DOWNLOAD_ENABLED)) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, "该文章当前不允许下载");
        }
        return documentService.read(
                blogPost.getDocumentId(), manage ? null : blogPost.getPublishedVersionId());
    }

    private BlogVo project(BlogPostDO blogPost, Document document, boolean manage) {
        List<DocumentResourceVo> resources;
        String previewIssue = null;
        try {
            resources = documentRenderer.resources(document, manage);
        } catch (ApiException failure) {
            // 管理预览按既有整次投影契约降级，不拼接新旧签名结果；公开投影继续报错。
            if (!manage || !failure.code().status().is5xxServerError()) {
                throw failure;
            }
            // 签名地址是派生数据。正文已经提交时不能把预览失败报告成保存失败。
            LOG.warn(
                    "Blog preview unavailable, blogId={}, versionId={}",
                    blogPost.getId(),
                    document.versionId());
            previewIssue = "正文已保存，资源预览暂时不可用，请稍后重新读取。";
            resources =
                    document.resources().stream()
                            .map(
                                    r ->
                                            DocumentConvert.MAPPER.to(
                                                    r, null, r.status(), r.originalUri(), r.errorMessage()))
                            .toList();
        }
        return BlogConvert.MAPPER.to(
                blogPost,
                document,
                state(blogPost),
                manage ? document.lockVersion() : document.version(),
                blogPost.getAllowVisitorDownload() == VISITOR_DOWNLOAD_ENABLED,
                previewIssue,
                resources);
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

    private boolean manage(String view) {
        if (MANAGE_VIEW.equals(view)) {
            return true;
        }
        if (PUBLIC_VIEW.equals(view)) {
            return false;
        }
        throw invalid("列表视图无效");
    }

    private String state(BlogPostDO blogPost) {
        return blogPost.getStatus() == PUBLISHED ? "PUBLISHED" : "DRAFT";
    }

    private BlogPostDO required(long id) {
        var blogPost = blogPostMapper.selectById(id);
        if (blogPost == null) {
            throw missing();
        }
        return blogPost;
    }

    private ApiException missing() {
        return new ApiException(ApiErrorCode.NOT_FOUND, "文章不存在或未公开");
    }

    private ApiException conflict() {
        return new ApiException(ApiErrorCode.DATA_CONFLICT, "文档已被修改，请重新读取后保存");
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
