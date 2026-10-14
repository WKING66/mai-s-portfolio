package dev.amai.portfolio.system.auth;

import cn.dev33.satoken.stp.StpInterface;
import dev.amai.portfolio.system.service.OwnerRoleService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AccountRoleProvider implements StpInterface {
    private final OwnerRoleService ownerRoleService;

    public AccountRoleProvider(OwnerRoleService ownerRoleService) {
        this.ownerRoleService = ownerRoleService;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 首版管理边界只使用站长角色，不颁发细粒度权限码。
        return List.of();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 直接读取当前数据库状态，避免沿用登录时缓存的站长角色。
        // 登录时写入的是数值主键；Sa-Token 回调中的 ID 类型不保证仍为 Long。
        return ownerRoleService.findRoles(Long.parseLong(loginId.toString()));
    }
}
