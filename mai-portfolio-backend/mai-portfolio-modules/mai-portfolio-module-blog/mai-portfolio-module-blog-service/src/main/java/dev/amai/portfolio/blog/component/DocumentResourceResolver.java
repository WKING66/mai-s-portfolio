package dev.amai.portfolio.blog.component;

import static dev.amai.portfolio.blog.constant.DocumentPolicy.*;

import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.blog.config.DocumentProperties;
import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.web.exception.*;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 将正文中的资源地址归档为稳定引用；不处理博客公开权限，不读取用户服务器文件路径。 */
@Component
public class DocumentResourceResolver {
    private static final String HTTP_PREFIX = "http://", HTTPS_PREFIX = "https://";
    private final AssetFileService files;
    private final AssetQueryService assets;
    private final RemoteResourceFetcher remote;
    private final DocumentProperties limits;

    public DocumentResourceResolver(
            AssetFileService files,
            AssetQueryService assets,
            RemoteResourceFetcher remote,
            DocumentProperties limits) {
        this.files = files;
        this.assets = assets;
        this.remote = remote;
        this.limits = limits;
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
        if (content == null
                || content.indexOf(0) >= 0
                || content.getBytes(StandardCharsets.UTF_8).length > limits.getMaxContentBytes())
            throw invalid("正文为空或超过限制");
        Map<String, DocumentResource> resources = new LinkedHashMap<>();
        Set<Long> created = new LinkedHashSet<>();
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                limits.getMaxFetchTotalMs());
        try {
            class References {
                        private String resolve(String uri, boolean image) {
                            ResourceType type =
                                    image ? ResourceType.IMAGE : ResourceType.ATTACHMENT;
                            // 同一类型和地址在本次正文中只归档一次；图片和附件不可混用校验规则。
                            String token = type + ":" + uri;
                            if (resources.containsKey(token))
                                return DocumentResource.REFERENCE_PREFIX
                                        + resources.get(token).key();
                            if (resources.size() >= limits.getMaxResources())
                                throw invalid("资源数量超过限制");
                            DocumentResource resource = null;
                            if (uri.startsWith(DocumentResource.REFERENCE_PREFIX)) {
                                var old =
                                        previous.get(
                                                uri.substring(
                                                        DocumentResource.REFERENCE_PREFIX
                                                                .length()));
                                if (old != null && old.type() == type) {
                                    resource = old;
                                    // 已归档引用保留 key；用户明确授权后，失败网络图片才允许重试。
                                    if (old.status() != ResourceStatus.READY
                                            && old.source() == ResourceSource.REMOTE
                                            && remoteRightsConfirmed)
                                        resource = create(old.originalUri(), type, old.key());
                                }
                            }
                            if (resource == null)
                                resource = create(uri, type, UUID.randomUUID().toString());
                            resources.put(token, resource);
                            return DocumentResource.REFERENCE_PREFIX + resource.key();
                        }

                        private DocumentResource create(String uri, ResourceType type, String key) {
                            boolean network =
                                    uri.regionMatches(true, 0, HTTP_PREFIX, 0, HTTP_PREFIX.length())
                                            || uri.regionMatches(true, 0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length());
                            ResourceSource source =
                                    network ? ResourceSource.REMOTE : ResourceSource.LOCAL;
                            String original =
                                    uri.length() <= MAX_RESOURCE_URI_LENGTH
                                            ? sourceReference(uri)
                                            : "[URI exceeds limit]";
                            Long id = null;
                            ResourceStatus status = ResourceStatus.INVALID;
                            AssetUsage usage =
                                    type == ResourceType.IMAGE
                                            ? AssetUsage.IMAGE
                                            : AssetUsage.ATTACHMENT;
                            try {
                                if (uri.length() > MAX_RESOURCE_URI_LENGTH) throw invalid("资源地址过长");
                                if (uri.startsWith(ASSET_REFERENCE_PREFIX)) {
                                    id = Long.valueOf(uri.substring(ASSET_REFERENCE_PREFIX.length()));
                                    var metadata =
                                            assets.findReadyMetadata(id)
                                                    .orElseThrow(() -> invalid("资源未就绪"));
                                    if (metadata.usage() != usage
                                            || !Objects.equals(
                                                    metadata.uploaderAccountId(), uploader))
                                        throw invalid("资源类型或归属无效");
                                } else {
                                    DocumentInputResource input;
                                    if (network) {
                                        if (System.nanoTime() > deadline)
                                            throw new IOException(
                                                    "Document fetch budget exhausted");
                                        if (!remoteRightsConfirmed) throw invalid("未确认网络图片使用权");
                                        input = remote.fetch(uri, usage.maxBytes(), deadline);
                                    } else {
                                        String path = referencePath(uri);
                                        input = local.get(path);
                                        if (input == null) throw invalid("未提供本地资源");
                                    }
                                    source = input.source();
                                    id =
                                            files.upload(
                                                    usage,
                                                    uploader,
                                                    input.path(),
                                                    input.contentType(),
                                                    input.content());
                                    created.add(id);
                                }
                                status = ResourceStatus.READY;
                            } catch (IOException failure) {
                                id = null;
                                status = ResourceStatus.FETCH_FAILED;
                            } catch (ApiException failure) {
                                id = null;
                                status =
                                        failure.code().status().is5xxServerError()
                                                ? ResourceStatus.FETCH_FAILED
                                                : ResourceStatus.INVALID;
                            } catch (NumberFormatException failure) {
                                id = null;
                                status = ResourceStatus.INVALID;
                            }
                            return new DocumentResource(key, type, source, original, id, status);
                        }
            }
            var references = new References();
            // 只归档真正的 AST 链接；不重排整篇正文，不改代码中的示例引用。
            String normalized = MarkdownDestinations.rewrite(content, (uri, image) ->
                    image || uri.startsWith(DocumentResource.REFERENCE_PREFIX)
                            || uri.startsWith(ASSET_REFERENCE_PREFIX) || isLocal(uri)
                            ? references.resolve(uri, image) : uri);
            if (normalized.getBytes(StandardCharsets.UTF_8).length > limits.getMaxContentBytes())
                throw invalid("规范化正文超过限制");
            return new PreparedDocument(normalized, List.copyOf(resources.values()), created);
        } catch (RuntimeException failure) {
            // 只补偿本次新建的未绑定文件，不能删除旧版本或已存在的编辑器上传资源。
            files.discardUnbound(created, uploader);
            throw failure;
        }
    }

    private static boolean isLocal(String uri) {
        return !uri.isBlank() && !uri.startsWith("#") && !uri.matches("(?i)^[a-z][a-z0-9+.-]*:.*");
    }

    /** 规范化上传映射的键：拒绝绝对路径、父目录逃逸和跨平台危险字符，不访问磁盘。 */
    public static String localPath(String uri) {
        if (uri == null
                || uri.isBlank()
                || uri.contains("\\")
                || uri.contains(":")
                || uri.matches(".*[<>\"|?*].*")
                || uri.startsWith("/")
                || uri.codePoints().anyMatch(Character::isISOControl)) throw invalid("本地资源路径无效");
        try {
            Path path = Path.of(uri).normalize();
            if (path.isAbsolute() || path.startsWith("..") || path.toString().isBlank())
                throw invalid("本地资源路径越界");
            return path.toString().replace('\\', '/');
        } catch (InvalidPathException failure) {
            throw invalid("本地资源路径无效");
        }
    }

    /** Markdown 地址可能编码空格；上传文件名则保持字面值。两者都只查询输入映射，不读取文件系统。 */
    static String referencePath(String uri) {
        if (uri.matches("(?i).*%(2f|5c).*")) throw invalid("资源地址不能编码路径分隔符");
        try {
            return localPath(URLDecoder.decode(uri.replace("+", "%2B"), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            throw invalid("资源地址编码无效");
        }
    }

    /** 来源是溯源信息，不是凭据存储；保留普通查询参数，隐藏常见鉴权参数。 */
    private static String sourceReference(String uri) {
        if (!uri.matches("(?i)^https?://.*")) return uri;
        return uri.replaceFirst("(?i)(https?://)[^/?#]*@", "$1")
                .replaceAll(
                        "(?i)([?&](?:token|access_token|auth|authorization|password|secret|api[-_]?key|signature|credential|x-amz-[^=&]*|ossaccesskeyid)=)[^&#]*",
                        "$1REDACTED");
    }

    private static ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
