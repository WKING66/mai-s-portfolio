package dev.amai.portfolio.blog.convert;

import dev.amai.portfolio.blog.entity.domain.*;
import dev.amai.portfolio.blog.entity.request.BlogRequest;
import dev.amai.portfolio.blog.entity.vo.*;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** 编译期装配博客投影；状态、版本选择、下载权限和预览降级均由业务层先确定。 */
@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BlogConvert {
    @Mapping(target = "id", source = "blogPost.id")
    @Mapping(target = "version", source = "documentSummary.version")
    @Mapping(target = "status", source = "status")
    BlogCardVo toCard(BlogPostDO blogPost, DocumentSummary documentSummary, String status);

    @Mapping(target = "id", source = "blogPost.id")
    @Mapping(target = "documentId", source = "document.id")
    @Mapping(target = "documentVersionId", source = "document.versionId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "version", source = "version")
    @Mapping(target = "resources", source = "resources")
    BlogVo toVo(
            BlogPostDO blogPost,
            Document document,
            String status,
            long version,
            boolean allowDownload,
            String previewIssue,
            List<DocumentResourceVo> resources);

    @Mapping(target = "version", ignore = true)
    @Mapping(target = "slug", constant = "")
    @Mapping(target = "summary", constant = "")
    BlogRequest toImportRequest(
            PreparedDocument prepared,
            String title,
            boolean allowDownload,
            boolean remoteRightsConfirmed);
}
