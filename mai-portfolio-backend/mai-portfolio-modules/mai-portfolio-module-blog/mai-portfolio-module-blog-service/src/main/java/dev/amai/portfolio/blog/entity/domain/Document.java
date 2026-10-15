package dev.amai.portfolio.blog.entity.domain;

import java.util.List;

/**
 * 持久化后的统一文档快照；来源格式不影响展示和导出。
 *
 * @param versionId 不可变内容版本的数据库 ID，公开版本指针引用它
 * @param version 该内容版本生成时的版本号，发布/下架不会改写它
 * @param lockVersion 当前文档乐观锁计数，管理端保存及状态变更需带回它
 * @param content 规范化 Markdown，资源使用 resource://key，不持久化临时 OSS URL
 */
public record Document(
        long id,

        long versionId,

        long version,

        long lockVersion,

        String title,

        String summary,

        String content,

        List<DocumentResource> resources) {
    public Document {
        resources = List.copyOf(resources);
    }
}
