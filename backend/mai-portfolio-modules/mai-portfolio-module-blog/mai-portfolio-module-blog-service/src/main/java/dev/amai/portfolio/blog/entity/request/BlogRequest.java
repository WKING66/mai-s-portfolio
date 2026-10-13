package dev.amai.portfolio.blog.entity.request;

/**
 * 传输输入；字段和发布业务规则统一由 BlogService 校验。
 * version 是管理快照的乐观锁计数，不是 document_version 的数据库 ID。
 * remoteRightsConfirmed 只授权本次解析中的网络图片抓取，不持久化为全局抓取许可。
 */
public record BlogRequest(
        Long version,
        String slug,
        String title,
        String summary,
        String content,
        boolean allowDownload,
        boolean remoteRightsConfirmed) {}
