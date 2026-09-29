package dev.amai.portfolio.system.service;

public interface SystemBootstrapService {
    /** 仅补齐缺失的站长账号，不修改其他业务域数据。 */
    void initialize();
}
