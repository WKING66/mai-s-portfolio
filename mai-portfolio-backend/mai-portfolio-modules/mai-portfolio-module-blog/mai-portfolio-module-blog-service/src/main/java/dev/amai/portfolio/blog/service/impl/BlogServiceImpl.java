package dev.amai.portfolio.blog.service.impl;

import static dev.amai.portfolio.blog.constant.BlogPolicy.*;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.blog.component.*;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.request.BlogRequest;
import dev.amai.portfolio.blog.entity.vo.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.blog.mapper.BlogPostMapper;
import dev.amai.portfolio.blog.service.*;
import dev.amai.portfolio.system.api.*;
import dev.amai.portfolio.web.exception.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.*;
import java.util.*;

/** 博客发布/权限与文档生命周期分别承载业务事实；外部文件 IO 不占用数据库事务。 */
@Service
public class BlogServiceImpl implements BlogService {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(BlogServiceImpl.class);
    private final BlogPostMapper blogs;
    private final DocumentService documents;
    private final DocumentResourceResolver resolver;
    private final DocumentParser parser;
    private final DocumentRenderer renderer;
    private final AssetFileService files;
    private final OwnerAuthorizationService owners;
    private final AccountIdentityService accounts;
    private final DocumentProperties limits;
    private final TransactionTemplate tx;

    private record SavedDraft(BlogPostDO blog, Document document) {}

    public BlogServiceImpl(
            BlogPostMapper blogs,
            DocumentService documents,
            DocumentResourceResolver resolver,
            DocumentParser parser,
            DocumentRenderer renderer,
            AssetFileService files,
            OwnerAuthorizationService owners,
            AccountIdentityService accounts,
            DocumentProperties limits,
            PlatformTransactionManager manager) {
        this.blogs = blogs;
        this.documents = documents;
        this.resolver = resolver;
        this.parser = parser;
        this.renderer = renderer;
        this.files = files;
        this.owners = owners;
        this.accounts = accounts;
        this.limits = limits;
        tx = new TransactionTemplate(manager);
    }

    @Override
    public BlogPageVo list(String view, int page, int size) {
        boolean manage = manage(view);
        if (manage) owners.requireOwner();
        if (page < 1 || page > MAX_PAGE_NUMBER || size < 1 || size > MAX_PAGE_SIZE)
            throw invalid("分页参数无效");
        var query = Wrappers.<BlogPostDO>lambdaQuery();
        if (!manage) query.eq(BlogPostDO::getStatus, PUBLISHED);
        query.orderByDesc(BlogPostDO::getPublishedAt).orderByDesc(BlogPostDO::getId);
        var result = blogs.selectPage(new Page<BlogPostDO>(page, size), query);
        // 公开列表只投影已发布版本；管理列表读取最新草稿，两者共用文档查询能力。
        Map<Long, Long> pointers = new HashMap<>();
        result.getRecords()
                .forEach(
                        b ->
                                pointers.put(
                                        b.getDocumentId(),
                                        manage ? null : b.getPublishedVersionId()));
        var summaries = documents.summaries(pointers);
        var cards =
                result.getRecords().stream()
                        .map(
                                b -> {
                                    var d = summaries.get(b.getDocumentId());
                                    if (d == null)
                                        throw new ApiException(
                                                ApiErrorCode.INTERNAL_ERROR, "文章文档版本缺失");
                                    return new BlogCardVo(
                                            b.getId(),
                                            b.getSlug(),
                                            d.title(),
                                            d.summary(),
                                            state(b),
                                            d.version(),
                                            b.getPublishedAt());
                                })
                        .toList();
        return new BlogPageVo(
                manage ? MANAGE_VIEW : PUBLIC_VIEW, page, size, result.getTotal(), cards);
    }

    @Override
    public BlogVo get(long id) {
        owners.requireOwner();
        var blog = required(id);
        return project(blog, documents.read(blog.getDocumentId(), null), true);
    }

    @Override
    public BlogVo publicArticle(String slug) {
        var blog =
                blogs.selectOne(
                        Wrappers.<BlogPostDO>lambdaQuery()
                                .eq(BlogPostDO::getSlug, slug)
                                .eq(BlogPostDO::getStatus, PUBLISHED));
        if (blog == null) throw missing();
        return project(
                blog, documents.read(blog.getDocumentId(), blog.getPublishedVersionId()), false);
    }

    @Override
    public BlogVo save(Long id, BlogRequest request) {
        // 外部资源准备先于短事务；提交时 Document 再做 CAS，避免预检查到提交之间丢失更新。
        owners.requireOwner();
        long account = accounts.currentAccountId();
        validate(request, id != null);
        BlogPostDO existing = id == null ? null : required(id);
        Map<String, DocumentResource> previous = new HashMap<>();
        if (existing != null) {
            if (documents.lockVersion(existing.getDocumentId()) != request.version())
                throw conflict();
            if (existing.getPublishedVersionId() != null
                    && !Objects.equals(existing.getSlug(), request.slug()))
                throw invalid("首次发布后文章标识不可修改");
            documents
                    .read(existing.getDocumentId(), null)
                    .resources()
                    .forEach(r -> previous.put(r.key(), r));
        }
        var prepared =
                resolver.prepare(
                        request.content(),
                        previous,
                        Map.of(),
                        request.remoteRightsConfirmed(),
                        account);
        return persist(existing, request, prepared, DocumentOrigin.EDITOR.code(), account);
    }

    @Override
    public BlogVo importDocument(MultipartFile file, List<MultipartFile> input, boolean rights) {
        // 上传的资源文件名是正文相对路径。总量上限指本地资源，正文另受 maxContentBytes 约束。
        owners.requireOwner();
        long account = accounts.currentAccountId();
        if (input.size() > limits.getMaxResources()) throw invalid("本地资源数量过多");
        Map<String, DocumentInputResource> local = new HashMap<>();
        long total = 0;
        for (var resource : input) {
            String path = DocumentResourceResolver.localPath(resource.getOriginalFilename());
            if (local.containsKey(path)) throw invalid("本地资源路径重复");
            total += resource.getSize();
            if (total > limits.getMaxImportBytes()) throw invalid("导入资源总大小超过限制");
            local.put(
                    path,
                    new DocumentInputResource(
                            path,
                            resource.getContentType(),
                            read(resource, limits.getMaxImportBytes())));
        }
        // 格式差异止于 Parser；资源归档、Document 保存和 Blog 创建不依赖原始格式。
        var parsed =
                parser.parse(
                        file == null ? null : file.getOriginalFilename(),
                        read(file, limits.getMaxContentBytes()),
                        local);
        var prepared =
                resolver.prepare(parsed.content(), Map.of(), parsed.resources(), rights, account);
        var request =
                new BlogRequest(
                        null,
                        "",
                        parsed.title().length() > MAX_TITLE_LENGTH
                                ? parsed.title().substring(0, MAX_TITLE_LENGTH)
                                : parsed.title(),
                        "",
                        prepared.content(),
                        true,
                        rights);
        return persist(null, request, prepared, parsed.origin().code(), account);
    }

    private BlogVo persist(
            BlogPostDO existing,
            BlogRequest request,
            PreparedDocument prepared,
            int origin,
            long account) {
        SavedDraft saved;
        try {
            // 原子提交文档版本、资源外键和博客关联；OSS 上传不放进数据库事务。
            saved =
                    tx.execute(
                            status -> {
                                var b = existing == null ? new BlogPostDO() : existing;
                                Document d =
                                        existing == null
                                                ? documents.create(
                                                        account,
                                                        origin,
                                                        request.title(),
                                                        request.summary(),
                                                        prepared)
                                                : documents.append(
                                                        b.getDocumentId(),
                                                        request.version(),
                                                        account,
                                                        request.title(),
                                                        request.summary(),
                                                        prepared);
                                b.setSlug(
                                        request.slug() == null || request.slug().isBlank()
                                                ? null
                                                : request.slug());
                                b.setDocumentId(d.id());
                                b.setAllowVisitorDownload(request.allowDownload()
                                        ? VISITOR_DOWNLOAD_ENABLED : VISITOR_DOWNLOAD_DISABLED);
                                b.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                                if (existing == null) {
                                    b.setStatus(DRAFT);
                                    b.setCreatedAt(b.getUpdatedAt());
                                    blogs.insert(b);
                                } else blogs.updateById(b);
                                return new SavedDraft(b, d);
                            });
        } catch (RuntimeException failure) {
            // 回滚后只回收本次新建且没有绑定的 Asset；存储删除失败由 Asset 层记录。
            files.discardUnbound(prepared.newlyCreatedAssets(), account);
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
        // 发布固定公开版本指针；下架仅改变可见状态，保留文档历史及普通资源地址。
        owners.requireOwner();
        var b = required(id);
        tx.executeWithoutResult(
                status -> {
                    documents.advanceLock(b.getDocumentId(), version);
                    var d = documents.read(b.getDocumentId(), null);
                    if (published) {
                        if (b.getSlug() == null
                                || d.title() == null
                                || d.title().isBlank()
                                || d.content().isBlank()) throw invalid("发布需要文章标识、标题和正文");
                        if (d.resources().stream()
                                .anyMatch(r -> r.status() != ResourceStatus.READY))
                            throw invalid("请先解决全部图片和附件问题");
                        if (renderer.resources(d, true).stream()
                                .anyMatch(r -> r.status() != ResourceStatus.READY))
                            throw invalid("引用资源当前不可用");
                        b.setPublishedVersionId(d.versionId());
                        b.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
                    }
                    b.setStatus(published ? PUBLISHED : DRAFT);
                    b.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    blogs.updateById(b);
                });
        LOG.info("Blog publication changed, blogId={}, published={}", id, published);
        return get(id);
    }

    @Override
    public Document exportDocument(long id, String view) {
        accounts.currentAccountId();
        boolean manage = manage(view);
        if (manage) owners.requireOwner();
        var b = required(id);
        if (!manage && (b.getStatus() != PUBLISHED
                || b.getAllowVisitorDownload() != VISITOR_DOWNLOAD_ENABLED))
            throw new ApiException(ApiErrorCode.FORBIDDEN, "该文章当前不允许下载");
        return documents.read(b.getDocumentId(), manage ? null : b.getPublishedVersionId());
    }

    private BlogVo project(BlogPostDO b, Document d, boolean manage) {
        List<DocumentResourceVo> resources;
        String previewIssue = null;
        try {
            resources = renderer.resources(d, manage);
        } catch (ApiException failure) {
            // fixme 好家伙错一个全死完，之前成功的也没留下
            // 主要的异常为 oss 获取 url 那里一次一次拿
            if (!manage || !failure.code().status().is5xxServerError()) throw failure;
            // 签名地址是派生数据。正文已经提交时不能把预览失败报告成保存失败。
            LOG.warn("Blog preview unavailable, blogId={}, versionId={}", b.getId(), d.versionId());
            previewIssue = "正文已保存，资源预览暂时不可用，请稍后重新读取。";
            resources =
                    d.resources().stream()
                            .map(
                                    r ->
                                            new DocumentResourceVo(
                                                    r.key(),
                                                    r.type(),
                                                    r.status(),
                                                    r.assetId(),
                                                    null,
                                                    null,
                                                    r.originalUri(),
                                                    null,
                                                    null))
                            .toList();
        }
        return new BlogVo(
                b.getId(),
                b.getSlug(),
                state(b),
                manage ? d.lockVersion() : d.version(),
                d.id(),
                d.versionId(),
                d.title(),
                d.summary(),
                d.content(),
                previewIssue,
                resources,
                b.getAllowVisitorDownload() == VISITOR_DOWNLOAD_ENABLED,
                b.getPublishedAt(),
                b.getUpdatedAt());
    }

    private void validate(BlogRequest r, boolean update) {
        if (r == null
                || r.content() == null
                || update && (r.version() == null || r.version() < 1)
                || r.title() != null && r.title().length() > MAX_TITLE_LENGTH
                || r.summary() != null && r.summary().length() > MAX_SUMMARY_LENGTH
                || r.slug() != null && r.slug().length() > MAX_SLUG_LENGTH)
            throw invalid("文章字段或版本无效");
    }

    private byte[] read(MultipartFile file, int max) {
        if (file == null || file.isEmpty() || file.getSize() > max) throw invalid("导入文件为空或超过大小限制");
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(max + 1);
            if (bytes.length > max) throw invalid("导入文件超过大小限制");
            return bytes;
        } catch (IOException failure) {
            throw invalid("导入文件读取失败");
        }
    }

    private boolean manage(String view) {
        if (MANAGE_VIEW.equals(view)) return true;
        if (PUBLIC_VIEW.equals(view)) return false;
        throw invalid("列表视图无效");
    }

    private String state(BlogPostDO b) {
        return b.getStatus() == PUBLISHED ? "PUBLISHED" : "DRAFT";
    }

    private BlogPostDO required(long id) {
        var b = blogs.selectById(id);
        if (b == null) throw missing();
        return b;
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
