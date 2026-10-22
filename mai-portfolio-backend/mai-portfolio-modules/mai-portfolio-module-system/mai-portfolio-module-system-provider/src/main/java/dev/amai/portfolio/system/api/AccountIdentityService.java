package dev.amai.portfolio.system.api;

import java.util.List;

/** 跨域获取当前有效账号身份，调用方不读取会话供应商或账号表。 */
public interface AccountIdentityService {
    long currentAccountId();

    /** 当前请求已验证账号的角色；用于上传者或 OWNER 等资源访问规则。 */
    List<String> currentRoles();
}
