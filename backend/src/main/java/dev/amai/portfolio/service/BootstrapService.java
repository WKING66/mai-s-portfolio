package dev.amai.portfolio.service;

public interface BootstrapService {
    /** 仅补齐缺失的初始数据，不覆盖站长后续编辑；整次初始化在事务内完成。 */
    void initialize();
}
