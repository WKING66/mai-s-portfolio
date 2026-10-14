package dev.amai.portfolio.blog.entity.domain;

import java.util.List;
import java.util.Set;

/** 准备完成、尚未提交的正文与资源；newlyCreatedAssets 仅包含本次归档成功的文件，用于回滚补偿。 */
public record PreparedDocument(
        String content, List<DocumentResource> resources, Set<Long> newlyCreatedAssets) {
    public PreparedDocument {
        resources = List.copyOf(resources);
        newlyCreatedAssets = Set.copyOf(newlyCreatedAssets);
    }
}
