package dev.amai.portfolio.blog.controller;

import static dev.amai.portfolio.blog.constant.BlogPolicy.*;

import cn.dev33.satoken.annotation.*;

import dev.amai.portfolio.blog.entity.request.*;
import dev.amai.portfolio.blog.entity.vo.*;
import dev.amai.portfolio.blog.service.*;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AuthConstants;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/** HTTP 参数与响应适配；权限/版本/资源规则由认证层及业务服务承担。 */
@RestController
@RequestMapping("/api/v1/blogs")
@Tag(name = "博客")
public class BlogController {
    private final BlogService blogs;
    private final DocumentExportService exports;

    public BlogController(BlogService blogs, DocumentExportService exports) {
        this.blogs = blogs;
        this.exports = exports;
    }

    @GetMapping
    @Operation(summary = "分页读取博客", description = "PUBLIC 默认读取公开版本；MANAGE 需要 OWNER，读取草稿投影。")
    public ResponseEntity<R<BlogPageVo>> list(
            @RequestParam(defaultValue = PUBLIC_VIEW) String view,
            @RequestParam(defaultValue = DEFAULT_PAGE) int page,
            @RequestParam(defaultValue = DEFAULT_PAGE_SIZE) int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(R.success(blogs.list(view, page, size)));
    }

    @GetMapping("/by-slug/{slug}")
    @Operation(summary = "读取公开文章", description = "下架文章返回 404；普通图片直接返回 OSS URL。")
    public ResponseEntity<R<BlogVo>> article(@PathVariable String slug) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(R.success(blogs.publicArticle(slug)));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @GetMapping("/{id}")
    @Operation(summary = "读取博客编辑快照")
    public R<BlogVo> get(@PathVariable long id) {
        return R.success(blogs.get(id));
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
    public R<BlogVo> create(@RequestBody BlogRequest input) {
        return R.success(blogs.save(null, input));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PatchMapping("/{id}")
    @Operation(
            summary = "保存新文档版本",
            description = "带回 version；保存不修改公开版本。",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<BlogVo> save(@PathVariable long id, @RequestBody BlogRequest input) {
        return R.success(blogs.save(id, input));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "导入 Markdown 草稿",
            description = "file 是 UTF-8 .md；resources 可提供正文相对路径对应的本地图片/附件。网络图片须明确确认使用权，失败资源保留草稿。",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<BlogVo> importDocument(
            @RequestPart MultipartFile file,
            @RequestPart(required = false) List<MultipartFile> resources,
            @RequestParam(defaultValue = "false") boolean remoteRightsConfirmed) {
        return R.success(
                blogs.importDocument(file, resources == null ? List.of() : resources, remoteRightsConfirmed));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping("/{id}/publish")
    @Operation(
            summary = "发布当前文档版本",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<BlogVo> publish(@PathVariable long id, @Valid @RequestBody BlogVersionRequest input) {
        return R.success(blogs.publish(id, input.version(), true));
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
            @PathVariable long id, @Valid @RequestBody BlogVersionRequest input) {
        return R.success(blogs.publish(id, input.version(), false));
    }

    @SaCheckLogin
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
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
        var plan = exports.prepare(blogs.exportDocument(id, view), includeResources);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(plan.contentType()))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(plan.filename())
                                .build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(plan.writer()::write);
    }
}
