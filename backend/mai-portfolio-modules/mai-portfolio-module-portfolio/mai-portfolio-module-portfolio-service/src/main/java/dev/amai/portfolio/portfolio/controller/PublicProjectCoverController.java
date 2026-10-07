package dev.amai.portfolio.portfolio.controller;

import dev.amai.portfolio.portfolio.service.ProjectCoverService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 项目公开卡片封面，不提供任意 assetId 下载入口。 */
@RestController
@Tag(name = "项目展示")
public class PublicProjectCoverController {
    private final ProjectCoverService covers;
    public PublicProjectCoverController(ProjectCoverService covers) { this.covers = covers; }

    @GetMapping("/api/v1/projects/{id}/cover")
    @Operation(summary = "读取已发布项目封面", description = "校验当前发布状态与封面引用；草稿、下架、未就绪或无图均返回 404。响应不缓存，防止下架后继续公开旧字节。")
    @ApiResponse(responseCode = "404", description = "项目或公开封面不存在")
    public ResponseEntity<StreamingResponseBody> open(
            @Parameter(description = "公开项目 ID", required = true) @PathVariable Long id) {
        var content = covers.openPublicCover(id);
        try {
            StreamingResponseBody body = output -> { try (content) { content.inputStream().transferTo(output); } };
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.byteSize()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline").header("X-Content-Type-Options", "nosniff").body(body);
        } catch (RuntimeException failure) {
            try { content.close(); } catch (java.io.IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }
}
