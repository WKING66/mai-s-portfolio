package dev.amai.portfolio.blog.component;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.ASSET_REFERENCE_PREFIX;
import static dev.amai.portfolio.blog.constant.DocumentPolicy.MAX_RESOURCE_URI_LENGTH;

import dev.amai.portfolio.asset.api.AssetFileService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.blog.entity.domain.DocumentInputResource;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import dev.amai.portfolio.blog.enums.ResourceSource;
import dev.amai.portfolio.blog.enums.ResourceStatus;
import dev.amai.portfolio.blog.enums.ResourceType;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/** 资源地址的校验、读取与统一 Asset 归档；不做引用去重，也不决定整篇准备的补偿时机。 */
final class DocumentResourceLoader {
    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";
    private final AssetFileService assetFileService;
    private final AssetQueryService assetQueryService;
    private final RemoteResourceFetcher remoteResourceFetcher;

    DocumentResourceLoader(
            AssetFileService assetFileService,
            AssetQueryService assetQueryService,
            RemoteResourceFetcher remoteResourceFetcher) {
        this.assetFileService = assetFileService;
        this.assetQueryService = assetQueryService;
        this.remoteResourceFetcher = remoteResourceFetcher;
    }

    DocumentResource load(
            String uri, ResourceType type, String key, DocumentPreparationContext context) {
        boolean network = uri.regionMatches(true, 0, HTTP_PREFIX, 0, HTTP_PREFIX.length())
                || uri.regionMatches(true, 0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length());
        ResourceSource source = network ? ResourceSource.REMOTE : ResourceSource.LOCAL;
        String original = uri.length() <= MAX_RESOURCE_URI_LENGTH
                ? sourceReference(uri) : "[URI exceeds limit]";
        Long assetId = null;
        ResourceStatus status = ResourceStatus.INVALID;
        AssetUsage usage = type == ResourceType.IMAGE ? AssetUsage.IMAGE : AssetUsage.ATTACHMENT;
        try {
            // 1. 引用现有资产只检查就绪、用途和本人归属，不重复上传或登记为新增资产。
            if (uri.length() > MAX_RESOURCE_URI_LENGTH) {
                throw invalid("资源地址过长");
            }
            if (uri.startsWith(ASSET_REFERENCE_PREFIX)) {
                assetId = existingAsset(uri, usage, context.uploader());
            } else {
                // 2. 读取输入资源后统一上传；先记录新增 ID，随后构造失败也能由编排层补偿。
                DocumentInputResource input = input(uri, network, usage, context);
                source = input.source();
                assetId = assetFileService.upload(
                        usage, context.uploader(), input.path(), input.contentType(), input.content());
                context.recordCreatedAsset(assetId);
            }
            status = ResourceStatus.READY;
        } catch (IOException failure) {
            assetId = null;
            status = ResourceStatus.FETCH_FAILED;
        } catch (ApiException failure) {
            assetId = null;
            status = failure.code().status().is5xxServerError()
                    ? ResourceStatus.FETCH_FAILED : ResourceStatus.INVALID;
        } catch (NumberFormatException failure) {
            assetId = null;
            status = ResourceStatus.INVALID;
        }
        // 单项已知失败仍返回状态供草稿展示；其他 RuntimeException 继续交给外层补偿。
        return new DocumentResource(key, type, source, original, assetId, status);
    }

    private long existingAsset(String uri, AssetUsage usage, long uploader) {
        long assetId = Long.valueOf(uri.substring(ASSET_REFERENCE_PREFIX.length()));
        var metadata = assetQueryService.findReadyMetadata(assetId)
                .orElseThrow(() -> invalid("资源未就绪"));
        if (metadata.usage() != usage || !Objects.equals(metadata.uploaderAccountId(), uploader)) {
            throw invalid("资源类型或归属无效");
        }
        return assetId;
    }

    private DocumentInputResource input(
            String uri, boolean network, AssetUsage usage, DocumentPreparationContext context)
            throws IOException {
        if (network) {
            // 共享绝对预算，检查顺序与原实现一致：先预算，再本次抓取授权。
            if (System.nanoTime() > context.fetchDeadlineNanos()) {
                throw new IOException("Document fetch budget exhausted");
            }
            if (!context.remoteRightsConfirmed()) {
                throw invalid("未确认网络图片使用权");
            }
            return remoteResourceFetcher.fetch(uri, usage.maxBytes(), context.fetchDeadlineNanos());
        }
        DocumentInputResource input = context.localResource(referencePath(uri));
        if (input == null) {
            throw invalid("未提供本地资源");
        }
        return input;
    }

    /** 规范化上传映射的键：拒绝绝对路径、父目录逃逸和跨平台危险字符，不访问磁盘。 */
    static String localPath(String uri) {
        if (uri == null
                || uri.isBlank()
                || uri.contains("\\")
                || uri.contains(":")
                || uri.matches(".*[<>\"|?*].*")
                || uri.startsWith("/")
                || uri.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("本地资源路径无效");
        }
        try {
            Path path = Path.of(uri).normalize();
            if (path.isAbsolute() || path.startsWith("..") || path.toString().isBlank()) {
                throw invalid("本地资源路径越界");
            }
            return path.toString().replace('\\', '/');
        } catch (InvalidPathException failure) {
            throw invalid("本地资源路径无效");
        }
    }

    /** Markdown 地址可能编码空格；上传文件名则保持字面值。两者都只查询输入映射，不读取文件系统。 */
    static String referencePath(String uri) {
        if (uri.matches("(?i).*%(2f|5c).*")) {
            throw invalid("资源地址不能编码路径分隔符");
        }
        try {
            return localPath(URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw invalid("资源地址编码无效");
        }
    }

    /** 来源是溯源信息，不是凭据存储；保留普通查询参数，隐藏常见鉴权参数。 */
    private static String sourceReference(String uri) {
        if (!uri.matches("(?i)^https?://.*")) {
            return uri;
        }
        return uri.replaceFirst("(?i)(https?://)[^/?#]*@", "$1")
                .replaceAll(
                        "(?i)([?&](?:token|access_token|auth|authorization|password|secret|api[-_]?key|signature|credential|x-amz-[^=&]*|ossaccesskeyid)=)[^&#]*",
                        "$1REDACTED");
    }

    private static ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
