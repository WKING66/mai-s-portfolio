package dev.amai.portfolio.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.system.api.TechTagData;
import dev.amai.portfolio.system.api.TechTagOptionData;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.system.enums.FeaturedStatus;
import dev.amai.portfolio.system.enums.TagKind;
import dev.amai.portfolio.system.enums.TechGroup;
import dev.amai.portfolio.system.mapper.TagMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TaxonomyQueryServiceImpl implements TaxonomyQueryService {
    private final TagMapper tags;

    public TaxonomyQueryServiceImpl(TagMapper tags) {
        this.tags = tags;
    }

    @Override
    public List<TechTagData> featuredTechTags() {
        return tags.selectList(Wrappers.<TagDO>lambdaQuery()
                .eq(TagDO::getKind, TagKind.TECH.code())
                .eq(TagDO::getIsFeatured, FeaturedStatus.FEATURED.code())
                .orderByAsc(TagDO::getGroupCode, TagDO::getSortOrder, TagDO::getId))
            .stream()
            .map(tag -> new TechTagData(tag.getName(), tag.getSlug(),
                TechGroup.fromCode(tag.getGroupCode()).name(), tag.getLogoKey()))
            .toList();
    }

    @Override
    public List<TechTagOptionData> techTagOptions() {
        return tags.selectList(Wrappers.<TagDO>lambdaQuery()
                .eq(TagDO::getKind, TagKind.TECH.code())
                .orderByAsc(TagDO::getGroupCode, TagDO::getSortOrder, TagDO::getId))
            .stream()
            .map(tag -> new TechTagOptionData(tag.getId(), tag.getName(), tag.getSlug(),
                TechGroup.fromCode(tag.getGroupCode()).name(), tag.getLogoKey()))
            .toList();
    }
}
