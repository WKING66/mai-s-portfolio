package dev.amai.portfolio.blog.entity.domain;

import dev.amai.portfolio.blog.enums.ContentStage;
import dev.amai.portfolio.system.api.TagData;
import java.util.List;

/** 一致性读取的当前内容；contentId 是槽位身份，revision 是管理写修订。 */
public record Document(
        long id,

        long contentId,

        long revision,

        ContentStage stage,

        boolean hasDraft,

        boolean hasPublished,

        String title,

        String summary,

        String content,

        List<DocumentResource> resources,

        List<TagData> tags) {
    public Document {
        resources = List.copyOf(resources);
        tags = List.copyOf(tags);
    }
}
