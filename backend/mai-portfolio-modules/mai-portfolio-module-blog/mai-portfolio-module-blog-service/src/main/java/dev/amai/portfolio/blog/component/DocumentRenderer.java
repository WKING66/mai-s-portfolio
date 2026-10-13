package dev.amai.portfolio.blog.component;

import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import dev.amai.portfolio.blog.entity.vo.DocumentResourceVo;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.web.exception.*;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** 资源投影和导出引用替换；URL 是派生结果，绝不回写正文。 */
@Component
public class DocumentRenderer {
    /** 缺失资源仅生成无效占位，不把内部 resource:// 协议或原始来源交给浏览器请求。 */
    private static final String UNAVAILABLE_RESOURCE_DESTINATION = "#resource-unavailable";
    private final AssetQueryService assets;

    public DocumentRenderer(AssetQueryService assets) {
        this.assets = assets;
    }

    /** 批量生成签名地址；manage 只控制来源字段投影，不赋予资源字节访问权限。 */
    public List<DocumentResourceVo> resources(Document document, boolean manage) {
        var result = new HashMap<Long, AssetResourceData>();
        for (AssetType type : List.of(AssetType.IMAGE, AssetType.FILE)) {
            var ids =
                    document.resources().stream()
                            .filter(
                                    r ->
                                            r.assetId() != null
                                                    && (r.type() == ResourceType.IMAGE
                                                                    ? AssetType.IMAGE
                                                                    : AssetType.FILE)
                                                            == type)
                            .map(r -> r.assetId())
                            .collect(Collectors.toSet());
            result.putAll(assets.findReady(ids, type));
        }
        return document.resources().stream()
                .map(
                        r -> {
                            var file = result.get(r.assetId());
                            return new DocumentResourceVo(
                                    r.key(),
                                    r.type(),
                                    file == null && r.status() == ResourceStatus.READY
                                            ? ResourceStatus.FETCH_FAILED
                                            : r.status(),
                                    r.assetId(),
                                    file == null ? null : file.resourceUrl(),
                                    file == null ? null : file.expiresAt(),
                                    manage ? r.originalUri() : null,
                                    file == null ? null : file.originalFilename(),
                                    file == null ? null : file.contentType());
                        })
                .toList();
    }

    /** 展示 URL 和 ZIP 内路径都通过相同 AST 替换，不用字符串正则替换整篇 Markdown。 */
    public String markdown(Document document, Map<String, String> destinations) {
        return MarkdownDestinations.rewrite(document.content(), (uri, image) -> {
            if (!uri.startsWith(DocumentResource.REFERENCE_PREFIX)) return uri;
            return Objects.requireNonNullElse(destinations.get(uri.substring(DocumentResource.REFERENCE_PREFIX.length())),
                    UNAVAILABLE_RESOURCE_DESTINATION);
        });
    }

}
