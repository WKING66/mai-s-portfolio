package dev.amai.portfolio.portfolio.controller;

import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.portfolio.service.ProfileMediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/public/profile/media")
@Tag(name = "公开个人媒体", description = "读取当前个人资料已公开引用的头像或简历")
public class PublicProfileMediaController {
    private final ProfileMediaService profileMedia;

    public PublicProfileMediaController(ProfileMediaService profileMedia) {
        this.profileMedia = profileMedia;
    }

    @GetMapping("/{assetId}")
    @Operation(summary = "读取公开个人媒体")
    public ResponseEntity<StreamingResponseBody> open(@PathVariable Long assetId) {
        AssetContentData content = profileMedia.openPublicMedia(assetId);
        try {
            StreamingResponseBody body = output -> {
                try (content) {
                    content.inputStream().transferTo(output);
                }
            };
            ContentDisposition disposition = content.assetType() == AssetType.DOCUMENT
                ? ContentDisposition.attachment()
                    .filename(filename(content, assetId), StandardCharsets.UTF_8).build()
                : ContentDisposition.inline()
                    .filename(filename(content, assetId), StandardCharsets.UTF_8).build();
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.byteSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                // 公开引用可被撤回，每次读取都必须重新授权，不能由缓存继续提供旧字节。
                .cacheControl(CacheControl.noStore())
                .body(body);
        } catch (RuntimeException setupFailure) {
            closeAfterSetupFailure(content, setupFailure);
            throw setupFailure;
        }
    }

    private String filename(AssetContentData content, Long assetId) {
        String original = content.originalFilename();
        return original == null || original.isBlank() ? "media-" + assetId : original;
    }

    private void closeAfterSetupFailure(AssetContentData content, RuntimeException failure) {
        try {
            content.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
