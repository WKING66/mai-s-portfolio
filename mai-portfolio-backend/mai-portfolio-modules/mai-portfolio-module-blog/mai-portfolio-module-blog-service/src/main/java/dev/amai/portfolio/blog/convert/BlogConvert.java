package dev.amai.portfolio.blog.convert;

import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.vo.*;
import java.util.List;
import org.mapstruct.*;
import org.mapstruct.factory.Mappers;

/** 博客身份与已选内容合成投影；管理字段裁剪由调用方明确指定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BlogConvert {
    BlogConvert MAPPER = Mappers.getMapper(BlogConvert.class);

    @Mapping(target = "status", source = "status")
    @Mapping(target = "hasDraft", source = "hasDraft")
    BlogCardVo to(BlogPostDO blogPost, DocumentSummary documentSummary,
            String status, Boolean hasDraft);

    @Mapping(target = "id", source = "blogPost.id")
    @Mapping(target = "documentId", source = "document.id")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "revision", source = "revision")
    @Mapping(target = "hasDraft", source = "hasDraft")
    @Mapping(target = "resources", source = "resources")
    BlogVo to(BlogPostDO blogPost, Document document, String status,
            Long revision, Boolean hasDraft, boolean resourcesReady, boolean allowDownload,
            String previewIssue, List<DocumentResourceVo> resources);
}
