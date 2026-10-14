package dev.amai.portfolio.system.auth;

import cn.dev33.satoken.stp.StpInterface;
import dev.amai.portfolio.system.service.OwnerRoleService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class AccountRoleProvider implements StpInterface {
    private final OwnerRoleService roles;

    public AccountRoleProvider(OwnerRoleService roles) {
        this.roles = roles;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 首版管理边界只使用站长角色，不颁发细粒度权限码。
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // TODO 走缓存
        // 登录时写入的是数值主键；Sa-Token 回调中的 ID 类型不保证仍为 Long。
        return roles.findRoles(Long.parseLong(loginId.toString()));
    }
}
