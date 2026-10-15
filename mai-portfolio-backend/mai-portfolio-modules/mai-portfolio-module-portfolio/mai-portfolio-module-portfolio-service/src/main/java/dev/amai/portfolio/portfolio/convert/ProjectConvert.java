package dev.amai.portfolio.portfolio.convert;

import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.portfolio.entity.domain.ProjectDO;
import dev.amai.portfolio.portfolio.entity.domain.ProjectLinkDO;
import dev.amai.portfolio.portfolio.entity.domain.ProjectMediaDO;
import dev.amai.portfolio.portfolio.entity.vo.ProjectCoverVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectEditorVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectItemVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectLinkVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectPageVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectTagVo;
import dev.amai.portfolio.portfolio.entity.vo.PublicProjectLinkVo;
import dev.amai.portfolio.portfolio.entity.vo.PublicProjectVo;
import dev.amai.portfolio.system.api.TechTagOptionData;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 项目字段投影；可见性、资源选择、状态编码和版本判断先由 Service 确定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProjectConvert {
    ProjectConvert MAPPER = Mappers.getMapper(ProjectConvert.class);

    ProjectTagVo to(TechTagOptionData tag);

    /** 管理字段是否公开由调用方决定，转换器不检查角色。 */
    @Mapping(target = "sortOrder", expression = "java(link.getSortOrder())")
    ProjectLinkVo to(ProjectLinkDO link, String type, Boolean visible);

    PublicProjectLinkVo to(ProjectLinkVo link);

    @Mapping(target = "assetId", source = "cover.mediaId")
    @Mapping(target = "url", source = "asset.resourceUrl")
    @Mapping(target = "expiresAt", source = "asset.expiresAt")
    ProjectCoverVo to(ProjectMediaDO cover, AssetResourceData asset, String alt);

    /** 公开投影类型本身不含后台状态、版本和隐藏外链。 */
    PublicProjectVo to(
            ProjectDO project,
            List<ProjectTagVo> tags,
            List<PublicProjectLinkVo> links,
            ProjectCoverVo cover);

    @Mapping(target = "status", source = "status")
    @Mapping(target = "sortOrder", expression = "java(project.getSortOrder())")
    ProjectEditorVo to(
            ProjectDO project,
            List<ProjectTagVo> tags,
            List<ProjectLinkVo> links,
            String status,
            boolean featured,
            ProjectCoverVo cover);

    ProjectPageVo to(String view, int page, int size, long total, List<ProjectItemVo> items);
}
