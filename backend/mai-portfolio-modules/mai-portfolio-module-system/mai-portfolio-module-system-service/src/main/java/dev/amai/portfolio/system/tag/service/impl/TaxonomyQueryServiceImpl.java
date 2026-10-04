package dev.amai.portfolio.system.tag.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.tag.api.TaxonomyQueryService;
import dev.amai.portfolio.system.tag.api.TechTagData;
import dev.amai.portfolio.system.tag.entity.domain.TagDO;
import dev.amai.portfolio.system.tag.enums.FeaturedStatus;
import dev.amai.portfolio.system.tag.enums.TagKind;
import dev.amai.portfolio.system.tag.enums.TechGroup;
import dev.amai.portfolio.system.tag.mapper.TagMapper;
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
}
