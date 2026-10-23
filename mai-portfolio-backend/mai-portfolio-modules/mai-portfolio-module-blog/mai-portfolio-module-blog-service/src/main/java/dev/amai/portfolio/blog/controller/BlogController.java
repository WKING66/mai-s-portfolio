package dev.amai.portfolio.blog.controller;

import static dev.amai.portfolio.blog.constant.BlogPolicy.*;
import static dev.amai.portfolio.blog.constant.DocumentPolicy.REQUEST_ID_PATTERN;

import cn.dev33.satoken.annotation.*;
import cn.hutool.core.collection.CollUtil;
import dev.amai.portfolio.blog.entity.request.*;
import dev.amai.portfolio.blog.entity.vo.*;
import dev.amai.portfolio.blog.enums.BlogAction;
import dev.amai.portfolio.blog.service.*;
import dev.amai.portfolio.common.PageRequest;
import dev.amai.portfolio.common.PageVO;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.common.ViewPageVO;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.api.OwnerView;
import dev.amai.portfolio.validation.ValidationGroups;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** HTTP 参数与响应适配；权限/修订/资源规则由认证层及业务服务承担。 */
@RestController
@RequestMapping("/api/v1/blogs")
@Tag(name = "博客")
public class BlogController {
    private final BlogService blogService;
    private final DocumentExportService documentExportService;

    private final DocumentImportTaskService documentImportTaskService;

    public BlogController(BlogService blogService, DocumentExportService documentExportService,
            DocumentImportTaskService documentImportTaskService) {
        this.blogService = blogService;
        this.documentExportService = documentExportService;
        this.documentImportTaskService = documentImportTaskService;
    }

    @OwnerView
    @GetMapping
    @Operation(summary = "分页读取博客", description = "PUBLIC 默认读取正式正文；MANAGE 需要 OWNER，读取草稿投影。")
    public ResponseEntity<R<ViewPageVO<BlogCardVo>>> list(
            @RequestParam(defaultValue = PUBLIC_VIEW) String view,
            @Valid @ParameterObject @ModelAttribute PageRequest pagination) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(R.success(blogService.list(view, pagination)));
    }

    @GetMapping("/by-slug/{slug}")
    @Operation(summary = "读取公开文章", description = "下架文章返回 404；普通图片直接返回 OSS URL。")
    public ResponseEntity<R<BlogVo>> article(@PathVariable String slug) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(R.success(blogService.publicArticle(slug)));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @GetMapping("/{id}")
    @Operation(summary = "读取当前编辑内容")
    public R<BlogVo> get(@PathVariable long id) {
        return R.success(blogService.get(id));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping
    @Operation(
            summary = "新建博客草稿",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<BlogVo> create(@Valid @RequestBody BlogRequest input) {
        return R.success(blogService.save(null, input));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PatchMapping("/{id}")
    @Operation(
            summary = "保存工作草稿",
            description = "带回 revision 和唯一 requestId；原地更新草稿，标签随草稿保存，公开正文不变；allowDownload 立即生效。",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<BlogVo> save(
            @PathVariable long id,
            @Validated(ValidationGroups.Update.class) @RequestBody BlogRequest input) {
        return R.success(blogService.save(id, input));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "导入 Markdown 草稿",
            description = "file 是 UTF-8 .md；resources 按正文相对路径提供本地图片/附件，兼容 Windows 引用分隔符。支持 Base64 data URI 图片，经统一 Asset 校验归档；网络图片须确认使用权。失败资源保留草稿，管理投影返回具体 errorMessage。",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<BlogVo> importDocument(
            @RequestPart MultipartFile file,
            @RequestPart(required = false) List<MultipartFile> resources,
            @RequestParam(defaultValue = "false") boolean remoteRightsConfirmed,
            @RequestParam(required = false)
            @Size(max = MAX_TAGS)
            List<@NotNull @Positive Long> tagIds,
            @RequestParam
            @NotBlank
            @Pattern(regexp = REQUEST_ID_PATTERN)
            String requestId) {
        return R.success(
                blogService.importDocument(
                        file, CollUtil.emptyIfNull(resources), remoteRightsConfirmed,
                        CollUtil.emptyIfNull(tagIds), requestId));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping("/{id}/publish")
    @Operation(
            summary = "发布已保存草稿并替换正式正文",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<BlogVo> publish(@PathVariable long id, @Valid @RequestBody BlogRevisionRequest input) {
        return R.success(blogService.transition(id, input, BlogAction.PUBLISH));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping("/{id}/unpublish")
    @Operation(
            summary = "下架文章，不删除文档或资源",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<BlogVo> unpublish(
            @PathVariable long id, @Valid @RequestBody BlogRevisionRequest input) {
        return R.success(blogService.transition(id, input, BlogAction.UNPUBLISH));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping("/{id}/restore")
    @Operation(summary = "恢复已下架的正式正文", description = "不提升草稿，不改变最近正式正文和发布时间。")
    public R<BlogVo> restore(@PathVariable long id, @Valid @RequestBody BlogRevisionRequest input) {
        return R.success(blogService.transition(id, input, BlogAction.RESTORE));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @DeleteMapping("/{id}/draft")
    @Operation(summary = "丢弃工作草稿", description = "必须已有正式正文；正式正文和公开状态不变。只有草稿的新文章请整篇删除。")
    public R<BlogVo> discardDraft(@PathVariable long id, @Valid @RequestBody BlogRevisionRequest input) {
        return R.success(blogService.transition(id, input, BlogAction.DISCARD_DRAFT));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @DeleteMapping("/{id}")
    @Operation(summary = "删除未上架文章", description = "上架中拒绝删除；正式正文仍有草稿也拒绝。仅解绑资源，不删除共享 Asset。")
    public R<Void> delete(@PathVariable long id, @Valid @RequestBody BlogRevisionRequest input) {
        blogService.delete(id, input);
        return R.success(null);
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @GetMapping("/imports")
    @Operation(summary = "分页读取导入记录", description = "可按 documentId 筛选；按记录 ID 倒序，可继续翻页查看历史导入。")
    public R<PageVO<DocumentImportTaskVo>> imports(
            @RequestParam(required = false)
            @Positive
            Long documentId,
            @Valid @ParameterObject @ModelAttribute PageRequest pagination) {
        return R.success(documentImportTaskService.page(documentId, pagination));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @GetMapping("/imports/{taskId}")
    @Operation(summary = "读取导入来源、结果和具体问题")
    public R<DocumentImportTaskVo> importTask(@PathVariable long taskId) {
        return R.success(documentImportTaskService.get(taskId));
    }

    @SaCheckLogin
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @OwnerView
    @GetMapping("/{id}/export")
    @Operation(
            summary = "导出 Markdown 或资源 ZIP",
            description =
                    "登录必需；PUBLIC 还需已发布且允许下载。MANAGE 需 OWNER。includeResources=true 且有资源时 ZIP，其余返回"
                            + " .md。")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable long id,
            @RequestParam(defaultValue = PUBLIC_VIEW) String view,
            @RequestParam(defaultValue = "false") boolean includeResources) {
        // 权限和资源就绪检查在提交响应前完成；writer 只在异步线程向容器输出流写数据。
        // Sa-Token 请求上下文由 Security starter 为 REQUEST/ASYNC 绑定，不跨线程复制上下文。
        var exportPlan =
                documentExportService.prepare(
                        blogService.exportDocument(id, view), includeResources);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(exportPlan.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(exportPlan.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(exportPlan.writer()::write);
    }
}
