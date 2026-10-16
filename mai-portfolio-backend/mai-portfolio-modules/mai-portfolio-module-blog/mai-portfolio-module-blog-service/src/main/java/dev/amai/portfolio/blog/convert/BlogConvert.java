package dev.amai.portfolio.blog.convert;

import dev.amai.portfolio.blog.entity.domain.BlogPostDO;
import dev.amai.portfolio.blog.entity.domain.Document;
import dev.amai.portfolio.blog.entity.domain.DocumentSummary;
import dev.amai.portfolio.blog.entity.vo.BlogCardVo;
import dev.amai.portfolio.blog.entity.vo.BlogVo;
import dev.amai.portfolio.blog.entity.vo.DocumentResourceVo;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 编译期装配博客投影；状态、版本选择、下载权限和预览降级均由业务层先确定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface BlogConvert {
    BlogConvert MAPPER = Mappers.getMapper(BlogConvert.class);

    /** 文章身份与已选版本摘要合成列表条目，状态由业务层提供。 */
    @Mapping(target = "status", source = "status")
    BlogCardVo to(BlogPostDO blogPost, DocumentSummary documentSummary, String status);

    /** 管理/公开投影共用字段映射，版本与资源可见性由调用方明确传入。 */
    @Mapping(target = "id", source = "blogPost.id")
    @Mapping(target = "documentId", source = "document.id")
    @Mapping(target = "documentVersionId", source = "document.versionId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "version", source = "version")
    @Mapping(target = "resources", source = "resources")
    BlogVo to(
            BlogPostDO blogPost,
            Document document,
            String status,
            long version,
            boolean allowDownload,
            String previewIssue,
            List<DocumentResourceVo> resources);
}
