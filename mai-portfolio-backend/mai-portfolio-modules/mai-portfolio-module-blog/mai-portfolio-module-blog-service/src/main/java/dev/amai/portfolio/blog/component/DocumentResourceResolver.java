package dev.amai.portfolio.blog.component;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import dev.amai.portfolio.blog.entity.domain.PreparedDocument;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 将正文中的资源地址归档为稳定引用；负责单次准备的编排与失败补偿。 */
@Component
public class DocumentResourceResolver {
    private final AssetFileService assetFileService;
    private final DocumentProperties documentProperties;
    private final DocumentResourceLoader documentResourceLoader;

    public DocumentResourceResolver(
            AssetFileService assetFileService,
            AssetQueryService assetQueryService,
            RemoteResourceFetcher remoteResourceFetcher,
            DocumentProperties documentProperties) {
        this.assetFileService = assetFileService;
        this.documentProperties = documentProperties;
        this.documentResourceLoader = new DocumentResourceLoader(
                assetFileService, assetQueryService, remoteResourceFetcher);
    }

    /**
     * previous 仅来自当前文档旧版本，local 仅来自本次上传；返回的新增 Asset 集合用于失败补偿。
     * 缺失或抓取失败的资源保留在草稿中，不能静默删除正文引用。
     */
    public PreparedDocument prepare(
            String content,
            Map<String, DocumentResource> previous,
            Map<String, DocumentInputResource> local,
            boolean remoteRightsConfirmed,
            long uploader) {
        // 1. 验证正文，创建仅属于本次调用的引用状态与共享抓取预算。
        validateContent(content);
        var context = new DocumentPreparationContext(
                previous, local, remoteRightsConfirmed, uploader,
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(documentProperties.getMaxFetchTotalMs()));
        try {
            // 2. 只处理真正的 AST 资源节点，不重排整篇正文或代码中的示例。
            var referenceResolver = new DocumentReferenceResolver(
                    context, documentResourceLoader, documentProperties);
            String normalized = MarkdownDestinations.rewrite(content, referenceResolver::resolve);
            validateNormalizedContent(normalized);
            // 3. 返回稳定引用与新增资产快照，已知的单项失败继续留在资源状态中。
            return new PreparedDocument(normalized, context.resources(), context.newlyCreatedAssets());
        } catch (RuntimeException failure) {
            // todo 补偿走异步
            // 只补偿本次新建的未绑定文件，不能删除旧版本或已存在的编辑器上传资源。
            assetFileService.discardUnbound(context.newlyCreatedAssets(), uploader);
            throw failure;
        }
    }

    /** 上传映射只规范化键，不读取服务器文件；保留既有导入调用边界。 */
    public static String localPath(String uri) {
        return DocumentResourceLoader.localPath(uri);
    }

    static String referencePath(String uri) {
        return DocumentResourceLoader.referencePath(uri);
    }

    private void validateContent(String content) {
        if (content == null || content.indexOf(0) >= 0
                || content.getBytes(StandardCharsets.UTF_8).length > documentProperties.getMaxContentBytes()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "正文为空或超过限制");
        }
    }

    private void validateNormalizedContent(String content) {
        // 规范化后仅重新检查容量，不额外增加原实现没有的输入规则。
        if (content.getBytes(StandardCharsets.UTF_8).length > documentProperties.getMaxContentBytes()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "规范化正文超过限制");
        }
    }
}
