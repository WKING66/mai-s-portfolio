package dev.amai.portfolio.blog.component;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.ASSET_REFERENCE_PREFIX;

import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import dev.amai.portfolio.blog.enums.ResourceSource;
import dev.amai.portfolio.blog.enums.ResourceStatus;
import dev.amai.portfolio.blog.enums.ResourceType;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.UUID;

/** 单次 Markdown 引用解析：选择归档范围、去重及复用历史引用；文件 IO 交给加载器。 */
final class DocumentReferenceResolver {
    private final DocumentPreparationContext context;
    private final DocumentResourceLoader documentResourceLoader;
    private final DocumentProperties documentProperties;

    DocumentReferenceResolver(
            DocumentPreparationContext context,
            DocumentResourceLoader documentResourceLoader,
            DocumentProperties documentProperties) {
        this.context = context;
        this.documentResourceLoader = documentResourceLoader;
        this.documentProperties = documentProperties;
    }

    String resolve(String uri, boolean image) {
        // 1. 普通网络超链接和锚点保持原样，只归档真实资源引用。
        if (!shouldArchive(uri, image)) {
            return uri;
        }
        ResourceType type = image ? ResourceType.IMAGE : ResourceType.ATTACHMENT;
        String token = type + ":" + uri;
        DocumentResource resource = context.resolvedReference(token);
        if (resource != null) {
            return resource.reference();
        }
        // 2. 去重后才检查数量；必须先于任何新增抓取或上传。
        if (context.resourceCount() >= documentProperties.getMaxResources()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "资源数量超过限制");
        }
        resource = previousResource(uri, type);
        if (resource == null) {
            resource = documentResourceLoader.load(uri, type, UUID.randomUUID().toString(), context);
        }
        // 3. 保留首次出现顺序，同一类型和地址只处理一次。
        context.rememberReference(token, resource);
        return resource.reference();
    }

    private DocumentResource previousResource(String uri, ResourceType type) {
        if (!uri.startsWith(DocumentResource.REFERENCE_PREFIX)) {
            return null;
        }
        DocumentResource previous = context.previousResource(
                uri.substring(DocumentResource.REFERENCE_PREFIX.length()));
        if (previous == null || previous.type() != type) {
            return null;
        }
        // 旧 key 永不因重试变化；只有明确授权的失败网络资源可以重新抓取。
        if (previous.status() != ResourceStatus.READY
                && previous.source() == ResourceSource.REMOTE
                && context.remoteRightsConfirmed()) {
            return documentResourceLoader.load(previous.originalUri(), type, previous.key(), context);
        }
        return previous;
    }

    private static boolean shouldArchive(String uri, boolean image) {
        return image
                || uri.startsWith(DocumentResource.REFERENCE_PREFIX)
                || uri.startsWith(ASSET_REFERENCE_PREFIX)
                || !uri.isBlank() && !uri.startsWith("#") && !uri.matches("(?i)^[a-z][a-z0-9+.-]*:.*");
    }
}
