package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.system.api.*;
import dev.amai.portfolio.system.convert.TaxonomyConvert;
import dev.amai.portfolio.system.entity.request.TagRequest;
import dev.amai.portfolio.system.enums.FeaturedStatus;
import dev.amai.portfolio.system.mapper.TagMapper;
import dev.amai.portfolio.system.service.TagService;
import dev.amai.portfolio.web.exception.*;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 统一创建主题/技术标签；名称在类别内唯一，slug 在全站唯一。 */
@Service
public class TagServiceImpl implements TagService {
    private final TagMapper tagMapper;

    public TagServiceImpl(TagMapper tagMapper) {
        this.tagMapper = tagMapper;
    }

    @Override
    @Transactional
    public TagData create(TagRequest request) {
        var tag = TaxonomyConvert.MAPPER.to(request);
        tag.setName(request.name().strip());
        tag.setNormalizedName(tag.getName().toLowerCase(Locale.ROOT));
        tag.setIsFeatured(FeaturedStatus.STANDARD.code());
        tag.setSortOrder(0);
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException failure) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "同类标签名称或标签标识已存在");
        }
        return TaxonomyConvert.MAPPER.to(tag, request.kind().name(),
                request.group() == null ? null : request.group().name());
    }
}
