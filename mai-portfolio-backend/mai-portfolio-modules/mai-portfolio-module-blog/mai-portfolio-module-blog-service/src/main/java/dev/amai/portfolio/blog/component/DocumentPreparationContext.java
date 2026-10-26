package dev.amai.portfolio.blog.component;

import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单次正文准备的状态；不作为 Spring 单例，不跨请求复用资源或抓取预算。 */
final class DocumentPreparationContext {
    private final Map<String, DocumentResource> previous;

    private final Map<String, DocumentInputResource> local;

    private final boolean remoteRightsConfirmed;

    private final long uploader;

    private final long fetchDeadlineNanos;

    private final Map<String, DocumentResource> resolvedReferences = new LinkedHashMap<>();

    private final Set<Long> newlyCreatedAssets = new LinkedHashSet<>();

    DocumentPreparationContext(
            Map<String, DocumentResource> previous,
            Map<String, DocumentInputResource> local,
            boolean remoteRightsConfirmed,
            long uploader,
            long fetchDeadlineNanos) {
        this.previous = previous;
        this.local = local;
        this.remoteRightsConfirmed = remoteRightsConfirmed;
        this.uploader = uploader;
        this.fetchDeadlineNanos = fetchDeadlineNanos;
    }

    DocumentResource resolvedReference(String token) {
        return resolvedReferences.get(token);
    }

    int resourceCount() {
        return resolvedReferences.size();
    }

    void rememberReference(String token, DocumentResource resource) {
        resolvedReferences.put(token, resource);
    }

    DocumentResource previousResource(String key) {
        return previous.get(key);
    }

    DocumentInputResource localResource(String path) {
        return local.get(path);
    }

    boolean remoteRightsConfirmed() {
        return remoteRightsConfirmed;
    }

    long uploader() {
        return uploader;
    }

    long fetchDeadlineNanos() {
        return fetchDeadlineNanos;
    }

    void recordCreatedAsset(long assetId) {
        newlyCreatedAssets.add(assetId);
    }

    List<DocumentResource> resources() {
        return List.copyOf(resolvedReferences.values());
    }

    Set<Long> newlyCreatedAssets() {
        // 返回快照而非内部可变集合；补偿顺序仍遵循本次创建顺序。
        return new LinkedHashSet<>(newlyCreatedAssets);
    }
}
