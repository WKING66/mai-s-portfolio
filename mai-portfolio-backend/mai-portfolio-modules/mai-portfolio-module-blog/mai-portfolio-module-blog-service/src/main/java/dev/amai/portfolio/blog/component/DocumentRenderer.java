package dev.amai.portfolio.blog.component;

import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.blog.convert.DocumentConvert;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.domain.DocumentResource;
import dev.amai.portfolio.blog.entity.vo.DocumentResourceVo;
import dev.amai.portfolio.blog.enums.*;
import dev.amai.portfolio.web.exception.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 资源投影和导出引用替换；URL 是派生结果，绝不回写正文。 */
@Component
public class DocumentRenderer {
    /** 缺失资源仅生成无效占位，不把内部 resource:// 协议或原始来源交给浏览器请求。 */
    private static final String UNAVAILABLE_RESOURCE_DESTINATION = "#resource-unavailable";

    private final AssetQueryService assetQueryService;

    public DocumentRenderer(AssetQueryService assetQueryService) {
        this.assetQueryService = assetQueryService;
    }

    /** 批量生成签名地址；manage 只控制来源字段投影，不赋予资源字节访问权限。 */
    public List<DocumentResourceVo> resources(Document document, boolean manage) {
        // 1. 去重后一次读取整篇文章的图片和附件，数量增加不会增加数据库查询次数。
        var ids = document.resources().stream()
                .map(DocumentResource::assetId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        var assetResourcesById = assetQueryService.findReady(ids);
        // 2. 当前可用性与版本资源状态分别投影；管理视图额外返回来源，但不提升权限。
        return document.resources().stream()
                .map(
                        r -> {
                            var assetResource = assetResourcesById.get(r.assetId());
                            AssetType expected = r.type() == ResourceType.IMAGE ? AssetType.IMAGE : AssetType.FILE;
                            if (assetResource != null && assetResource.type() != expected) {
                                // 同一 Asset 不能同时冒充图片和附件；错误绑定按不可用资源展示，不能发出错误类型链接。
                                assetResource = null;
                            }
                            ResourceStatus status =
                                    assetResource == null && r.status() == ResourceStatus.READY
                                            ? ResourceStatus.FETCH_FAILED
                                            : r.status();
                            // 当前失效状态和来源可见性由此处决定；空资产字段交给生成映射统一处理。
                            return DocumentConvert.MAPPER.to(
                                    r, assetResource, status, manage ? r.originalUri() : null,
                                    manage ? resourceError(r, assetResource) : null);
                        })
                .toList();
    }

    private static String resourceError(DocumentResource resource, AssetResourceData asset) {
        if (resource.status() == ResourceStatus.READY && asset == null) {
            return "已绑定的资源不存在或尚未就绪，请重新上传";
        }
        return resource.errorMessage();
    }

    /** 展示 URL 和 ZIP 内路径都通过相同 AST 替换，不用字符串正则替换整篇 Markdown。 */
    public String markdown(Document document, Map<String, String> destinations) {
        return MarkdownDestinations.rewrite(
                document.content(),
                (uri, image) -> {
                    if (!uri.startsWith(DocumentResource.REFERENCE_PREFIX)) {
                        return uri;
                    }
                    return Objects.requireNonNullElse(
                            destinations.get(
                                    uri.substring(DocumentResource.REFERENCE_PREFIX.length())),
                            UNAVAILABLE_RESOURCE_DESTINATION);
                });
    }
}
