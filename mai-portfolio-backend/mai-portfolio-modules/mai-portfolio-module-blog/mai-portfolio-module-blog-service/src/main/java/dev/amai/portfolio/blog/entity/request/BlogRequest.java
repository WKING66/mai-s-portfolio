package dev.amai.portfolio.blog.entity.request;

import static dev.amai.portfolio.blog.constant.BlogPolicy.*;

import dev.amai.portfolio.validation.ValidationGroups;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 请求形状与长度在 HTTP 边界校验；草稿允许空标题和空正文，发布完整性由 BlogService 判断。
 * version 是管理快照的乐观锁计数，不是 document_version 的数据库 ID；仅更新时必填。
 * remoteRightsConfirmed 只授权本次解析中的网络图片抓取，不持久化为全局抓取许可。
 */
public record BlogRequest(
        @NotNull(groups = ValidationGroups.Update.class, message = "更新文章必须提供版本")
        @Positive(groups = ValidationGroups.Update.class, message = "版本必须为正整数")
        Long version,

        @Size(max = MAX_SLUG_LENGTH, message = "文章标识长度不能超过 {max}")
        String slug,

        @Size(max = MAX_TITLE_LENGTH, message = "标题长度不能超过 {max}")
        String title,

        @Size(max = MAX_SUMMARY_LENGTH, message = "摘要长度不能超过 {max}")
        String summary,

        @NotNull(message = "正文不能为 null")
        String content,

        boolean allowDownload,

        boolean remoteRightsConfirmed) {}
