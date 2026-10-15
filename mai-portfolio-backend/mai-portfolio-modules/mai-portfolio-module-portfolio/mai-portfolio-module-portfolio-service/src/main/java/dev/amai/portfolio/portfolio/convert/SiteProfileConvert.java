package dev.amai.portfolio.portfolio.convert;

import dev.amai.portfolio.portfolio.entity.domain.SiteConfigDO;
import dev.amai.portfolio.portfolio.entity.vo.SiteProfileVo;
import dev.amai.portfolio.portfolio.entity.vo.TechTagVo;
import dev.amai.portfolio.system.api.TechTagData;
import java.time.Instant;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 网站资料字段装配；资产查询及签名地址有效期由统一 Asset 能力提供。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SiteProfileConvert {
    SiteProfileConvert MAPPER = Mappers.getMapper(SiteProfileConvert.class);

    TechTagVo to(TechTagData tag);

    SiteProfileVo to(
            SiteConfigDO profile,
            String avatarUrl,
            String resumeUrl,
            List<TechTagVo> techStack,
            Instant avatarExpiresAt,
            Instant resumeExpiresAt);
}
