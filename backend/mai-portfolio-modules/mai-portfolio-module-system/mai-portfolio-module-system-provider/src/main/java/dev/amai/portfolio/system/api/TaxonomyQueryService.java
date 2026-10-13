package dev.amai.portfolio.system.api;

import java.util.List;

/** 分类标签域对外提供的只读能力。 */
public interface TaxonomyQueryService {
    /** 按分类与展示顺序返回首页公开技术栈。 */
    List<TechTagData> featuredTechTags();

    /** 全部可选择的技术标签；主题标签不用于项目能力关联。 */
    List<TechTagOptionData> techTagOptions();

    /** 校验标签选择业务的支持范围；当前只接受 TECH，其他类型不自动降级。 */
    List<TechTagOptionData> techTagOptions(String requestedKind);
}
