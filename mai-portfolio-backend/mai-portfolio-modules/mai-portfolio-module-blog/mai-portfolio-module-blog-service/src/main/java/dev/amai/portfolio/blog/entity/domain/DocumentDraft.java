package dev.amai.portfolio.blog.entity.domain;

import dev.amai.portfolio.system.api.TagData;
import java.util.List;

/** 已完成来源解析及标签校验的一次草稿写命令，不携带 HTTP 或 OSS URL。 */
public record DocumentDraft(
        String title,

        String summary,

        PreparedDocument prepared,

        List<TagData> tags,

        long editor,

        String requestId,

        String requestHash) {}
