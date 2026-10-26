package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.api.TagData;
import dev.amai.portfolio.system.entity.request.TagRequest;

/** 标签域写边界，不按博客或项目调用方复制标签业务。 */
public interface TagService {
    TagData create(TagRequest request);
}
