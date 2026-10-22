package dev.amai.portfolio.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.api.TagData;
import dev.amai.portfolio.system.api.TagKind;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.system.api.TechTagData;
import dev.amai.portfolio.system.api.TechTagOptionData;
import dev.amai.portfolio.system.convert.TaxonomyConvert;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.system.enums.FeaturedStatus;
import dev.amai.portfolio.system.enums.TechGroup;
import dev.amai.portfolio.system.mapper.TagMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TaxonomyQueryServiceImpl implements TaxonomyQueryService {
    private final TagMapper tagMapper;

    public TaxonomyQueryServiceImpl(TagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    /** 博客可选两类标签；项目指定 TECH 仍只得到技术标签，不另建一套目录查询。 */
    @Override
    public List<TagData> tagOptions(String kind) {
        TagKind selected = null;
        if (kind != null) {
            try {
                selected = TagKind.valueOf(kind);
            } catch (IllegalArgumentException failure) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "标签类别仅支持 TOPIC 或 TECH");
            }
        }
        var query = Wrappers.<TagDO>lambdaQuery();
        if (selected != null) {
            query.eq(TagDO::getKind, selected.code());
        }
        return tagMapper.selectList(query.orderByAsc(TagDO::getKind)
                .orderByAsc(selected == TagKind.TECH, TagDO::getGroupCode)
                .orderByAsc(TagDO::getSortOrder, TagDO::getId))
                .stream()
                .map(this::tagData)
                .toList();
    }

    /** 保存前确认所选标签存在且不重复，避免文章保存了界面无法展示的无效标签。 */
    @Override
    public List<TagData> requireTags(List<Long> tagIds) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        var uniqueIds = new LinkedHashSet<>(tagIds);
        if (uniqueIds.size() != tagIds.size()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "标签选择重复");
        }
        var tags = tagMapper.selectByIds(uniqueIds);
        if (tags.size() != uniqueIds.size()) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED, "所选标签不存在，请重新选择");
        }
        // 单次批量查询，按请求顺序返回；不为每个标签发起循环数据库查询。
        var byId = tags.stream()
                .collect(Collectors.toMap(TagDO::getId, this::tagData));
        return tagIds.stream()
                .map(byId::get)
                .toList();
    }

    private TagData tagData(TagDO tag) {
        String kind = TagKind.fromCode(tag.getKind()).name();
        String group = tag.getKind() == TagKind.TECH.code()
                ? TechGroup.fromCode(tag.getGroupCode()).name() : null;
        return TaxonomyConvert.MAPPER.to(tag, kind, group);
    }

    @Override
    public List<TechTagData> featuredTechTags() {
        return tagMapper
                .selectList(
                        Wrappers.<TagDO>lambdaQuery()
                                .eq(TagDO::getKind, TagKind.TECH.code())
                                .eq(TagDO::getIsFeatured, FeaturedStatus.FEATURED.code())
                                .orderByAsc(TagDO::getGroupCode, TagDO::getSortOrder, TagDO::getId))
                .stream()
                .map(
                        tag ->
                                TaxonomyConvert.MAPPER.to(
                                        tag, TechGroup.fromCode(tag.getGroupCode()).name()))
                .toList();
    }

    /** 项目仍使用既有技术标签投影，查询和分类规则复用共享标签列表。 */
    @Override
    public List<TechTagOptionData> techTagOptions() {
        return tagOptions(TagKind.TECH.name())
                .stream()
                .map(TaxonomyConvert.MAPPER::toOption)
                .toList();
    }
}
