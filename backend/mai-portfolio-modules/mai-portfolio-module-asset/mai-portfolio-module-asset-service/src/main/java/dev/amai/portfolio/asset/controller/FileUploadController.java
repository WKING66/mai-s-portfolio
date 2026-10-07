package dev.amai.portfolio.asset.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.asset.constant.FileUploadConstants;
import dev.amai.portfolio.asset.entity.vo.UploadedFileVo;
import dev.amai.portfolio.asset.service.FileUploadService;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.system.api.AuthConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 通用文件入口的已实现图片子能力，不接受项目源码、任意路径或未支持的文档格式。 */
@RestController
@RequestMapping(FileUploadConstants.IMAGE_PATH)
@SaCheckRole(AuthConstants.OWNER_ROLE)
@SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
@Tag(name = "文件上传", description = "独立上传、业务保存时绑定；图片校验和 OSS 处理共用组件")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "未登录"),
    @ApiResponse(responseCode = "403", description = "无站长权限、账号停用、CSRF 或来源无效"),
    @ApiResponse(responseCode = "422", description = "图片内容、大小或像素上限不满足"),
    @ApiResponse(responseCode = "500", description = "上传失败，不返回对象键或厂商错误")
})
public class FileUploadController {
    private final FileUploadService uploads;
    public FileUploadController(FileUploadService uploads) { this.uploads = uploads; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiLog
    @Operation(summary = "上传项目展示图片", description = "PNG/JPEG，不超过 8 MiB、4096×4096。服务端重编码后写入配置的 OSS。返回资产 ID，尚未发布或绑定；不上传项目代码。",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public R<UploadedFileVo> upload(
            @Parameter(description = "项目截图或封面文件", required = true, schema = @Schema(type = "string", format = "binary"))
            @RequestPart("file") MultipartFile file) {
        return R.success(uploads.uploadProjectImage(file));
    }

    @GetMapping("/{assetId}")
    @Operation(summary = "预览上传的项目图片", description = "OWNER 专用预览，不能用于公开卡片或读取私人头像。")
    @ApiResponse(responseCode = "404", description = "资产未就绪或用途不符")
    public ResponseEntity<StreamingResponseBody> preview(
            @Parameter(description = "上传接口返回的资产 ID") @PathVariable Long assetId) {
        var content = uploads.openProjectImage(assetId);
        try {
            StreamingResponseBody body = output -> { try (content) { content.inputStream().transferTo(output); } };
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.byteSize()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff").body(body);
        } catch (RuntimeException failure) {
            try { content.close(); } catch (java.io.IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
}
