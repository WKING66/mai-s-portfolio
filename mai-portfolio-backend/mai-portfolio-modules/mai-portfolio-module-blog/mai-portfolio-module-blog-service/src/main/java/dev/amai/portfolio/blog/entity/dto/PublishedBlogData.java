package dev.amai.portfolio.blog.entity.dto;

import dev.amai.portfolio.blog.entity.domain.BlogPostDO;
import dev.amai.portfolio.blog.entity.domain.DocumentContentDO;
import dev.amai.portfolio.blog.entity.domain.DocumentDO;
import lombok.Getter;
import lombok.Setter;

/** 一次读出的公开文章主体；复用已有实体字段，不另复制一套文章属性。 */
@Getter
@Setter
public class PublishedBlogData {
    private BlogPostDO blog;

    private DocumentDO document;

    private DocumentContentDO content;
}
