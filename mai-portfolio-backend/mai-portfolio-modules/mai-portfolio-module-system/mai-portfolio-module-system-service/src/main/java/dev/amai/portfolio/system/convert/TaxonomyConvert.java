package dev.amai.portfolio.system.convert;

import dev.amai.portfolio.system.api.TechTagData;
import dev.amai.portfolio.system.api.TechTagOptionData;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.system.entity.vo.TechTagOptionVo;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 共享技术标签字段投影；标签范围、展示条件和分组编码由分类服务决定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TaxonomyConvert {
    TaxonomyConvert MAPPER = Mappers.getMapper(TaxonomyConvert.class);

    TechTagData to(TagDO tag, String group);

    /** 与技术栈投影参数相同，仅返回类型不同，使用明确名称避免非法重载。 */
    TechTagOptionData toOption(TagDO tag, String group);

    TechTagOptionVo to(TechTagOptionData tag);
}
