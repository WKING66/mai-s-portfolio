package dev.amai.portfolio.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.system.api.TechTagData;
import dev.amai.portfolio.system.api.TechTagOptionData;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.system.enums.FeaturedStatus;
import dev.amai.portfolio.system.enums.TagKind;
import dev.amai.portfolio.system.enums.TechGroup;
import dev.amai.portfolio.system.mapper.TagMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TaxonomyQueryServiceImpl implements TaxonomyQueryService {
    private final TagMapper tagMapper;

    public TaxonomyQueryServiceImpl(TagMapper tagMapper) {
        this.tagMapper = tagMapper;
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
                                new TechTagData(
                                        tag.getName(),
                                        tag.getSlug(),
                                        TechGroup.fromCode(tag.getGroupCode()).name(),
                                        tag.getLogoKey()))
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
                                new TechTagOptionData(
                                        tag.getId(),
                                        tag.getName(),
                                        tag.getSlug(),
                                        TechGroup.fromCode(tag.getGroupCode()).name(),
                                        tag.getLogoKey()))
                .toList();
    }
}
