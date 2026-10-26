package dev.amai.portfolio.blog.entity.vo;

import dev.amai.portfolio.blog.enums.*;
import java.time.Instant;

/** 版本资源的当前展示投影；未就绪文件没有 URL，originalUri 与 errorMessage 仅对管理投影开放。 */
public record DocumentResourceVo(
        String key,

        ResourceType type,

        ResourceStatus status,

        Long assetId,

        String resourceUrl,

        Instant expiresAt,

        String originalUri,

        String filename,

        String contentType,

        String errorMessage) {}
