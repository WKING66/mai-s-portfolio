package dev.amai.portfolio.system.api;

import java.util.List;

/** 分类标签域对外提供的只读能力。 */
public interface TaxonomyQueryService {
    /** 按分类与展示顺序返回首页公开技术栈。 */
    List<TechTagData> featuredTechTags();
}
