package dev.amai.portfolio.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.system.api.TagData;
import java.util.LinkedHashSet;
import dev.amai.portfolio.system.api.TechTagData;
import dev.amai.portfolio.system.api.TechTagOptionData;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.convert.TaxonomyConvert;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.system.enums.FeaturedStatus;
import dev.amai.portfolio.system.enums.TagKind;
import dev.amai.portfolio.system.enums.TechGroup;
import dev.amai.portfolio.system.mapper.TagMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class TaxonomyQueryServiceImpl implements TaxonomyQueryService {
    private final TagMapper tagMapper;

    public TaxonomyQueryServiceImpl(TagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

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
        return tagMapper.selectList(query.orderByAsc(TagDO::getKind, TagDO::getSortOrder, TagDO::getId))
                .stream().map(this::tagData).toList();
    }

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
        var byId = tags.stream().collect(Collectors.toMap(TagDO::getId, this::tagData));
        return tagIds.stream().map(byId::get).toList();
    }

    private TagData tagData(TagDO tag) {
        String kind = tag.getKind() == TagKind.TOPIC.code() ? TagKind.TOPIC.name() : TagKind.TECH.name();
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

    @Override
    public List<TechTagOptionData> techTagOptions() {
        return techTagOptions(TagKind.TECH.name());
    }

    @Override
    public List<TechTagOptionData> techTagOptions(String requestedKind) {
        if (!TagKind.TECH.name().equals(requestedKind)) {
            throw new ApiException(
                    ApiErrorCode.VALIDATION_FAILED, SystemMessageConstants.TAG_KIND_INVALID);
        }
        return tagMapper
                .selectList(
                        Wrappers.<TagDO>lambdaQuery()
                                .eq(TagDO::getKind, TagKind.TECH.code())
                                .orderByAsc(TagDO::getGroupCode, TagDO::getSortOrder, TagDO::getId))
                .stream()
                .map(
                        tag ->
                                TaxonomyConvert.MAPPER.toOption(
                                        tag, TechGroup.fromCode(tag.getGroupCode()).name()))
                .toList();
    }
}
