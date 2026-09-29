package dev.amai.portfolio.system.service;

import java.util.List;

public interface OwnerRoleService {
    /** 每次鉴权从账号当前状态计算角色，不从登录时的会话快照读取。 */
    List<String> findRoles(long accountId);
}
