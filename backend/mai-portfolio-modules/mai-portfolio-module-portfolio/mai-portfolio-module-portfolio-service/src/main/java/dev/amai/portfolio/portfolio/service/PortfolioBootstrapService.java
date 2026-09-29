package dev.amai.portfolio.portfolio.service;

public interface PortfolioBootstrapService {
    /** 只补齐缺失的初始公开资料，不覆盖站长后续编辑。 */
    void initialize();
}
