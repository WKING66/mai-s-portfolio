package dev.amai.portfolio.system.convert;

import dev.amai.portfolio.system.api.TagData;
import dev.amai.portfolio.system.api.TechTagData;
import dev.amai.portfolio.system.api.TechTagOptionData;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.system.entity.request.TagRequest;
import dev.amai.portfolio.system.entity.vo.TechTagOptionVo;
import dev.amai.portfolio.system.enums.TagKind;
import dev.amai.portfolio.system.enums.TechGroup;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.factory.Mappers;

/** 共享技术标签字段投影；标签范围、展示条件和分组编码由分类服务决定。 */
@Mapper(unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TaxonomyConvert {
    TaxonomyConvert MAPPER = Mappers.getMapper(TaxonomyConvert.class);

    @Mapping(target = "kind", source = "kind")
    TagData to(TagDO tag, String kind, String group);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "normalizedName", ignore = true)
    @Mapping(target = "isFeatured", ignore = true)
    @Mapping(target = "sortOrder", ignore = true)
    @Mapping(target = "groupCode", source = "group")
    TagDO to(TagRequest request);

    default Integer to(TagKind kind) {
        return kind.code();
    }

    default Integer to(TechGroup group) {
        return group == null ? null : group.code();
    }

    TechTagData to(TagDO tag, String group);

    /** 与技术栈投影参数相同，仅返回类型不同，使用明确名称避免非法重载。 */
    TechTagOptionData toOption(TagDO tag, String group);

    TechTagOptionVo to(TechTagOptionData tag);
}
