package dev.amai.portfolio.blog.entity.domain;

import dev.amai.portfolio.blog.enums.DocumentOrigin;
import java.util.Map;

/** Parser 输出正文和输入资源；后续只消费此统一结果。 */
public record ParsedDocument(
        DocumentOrigin origin,

        String title,

        String content,

        Map<String, DocumentInputResource> resources) {
    public ParsedDocument {
        resources = Map.copyOf(resources);
    }
}
