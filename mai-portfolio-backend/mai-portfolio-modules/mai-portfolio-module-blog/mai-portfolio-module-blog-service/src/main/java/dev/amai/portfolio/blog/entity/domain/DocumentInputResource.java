package dev.amai.portfolio.blog.entity.domain;

import dev.amai.portfolio.blog.enums.ResourceSource;

/** 尚未归档的解析输入资源，不含 OSS 状态或业务权限。 */
public record DocumentInputResource(
        String path,

        String contentType,

        byte[] content,

        ResourceSource source) {
    public DocumentInputResource(String path, String contentType, byte[] content) {
        this(path, contentType, content, ResourceSource.LOCAL);
    }
}
