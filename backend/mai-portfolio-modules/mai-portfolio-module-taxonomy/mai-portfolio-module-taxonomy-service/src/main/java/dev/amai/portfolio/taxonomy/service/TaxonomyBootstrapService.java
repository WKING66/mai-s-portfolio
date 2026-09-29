package dev.amai.portfolio.taxonomy.service;

public interface TaxonomyBootstrapService {
    /** 只补齐缺失的默认技术标签，不覆盖站长后续维护的数据。 */
    void initialize();
}
