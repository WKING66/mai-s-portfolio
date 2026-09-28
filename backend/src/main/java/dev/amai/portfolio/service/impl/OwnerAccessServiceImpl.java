package dev.amai.portfolio.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.common.AuthConstants;
import dev.amai.portfolio.common.ApiErrorCode;
import dev.amai.portfolio.common.ApiException;
import dev.amai.portfolio.common.MessageConstants;
import dev.amai.portfolio.entity.UserAccountEntity;
import dev.amai.portfolio.mapper.UserAccountMapper;
import dev.amai.portfolio.service.OwnerAccessService;
import dev.amai.portfolio.service.OwnerIdentity;
import org.springframework.stereotype.Service;

@Service
public class OwnerAccessServiceImpl implements OwnerAccessService {
    private final UserAccountMapper accounts;

    public OwnerAccessServiceImpl(UserAccountMapper accounts) {
        this.accounts = accounts;
    }

    @Override
    public OwnerIdentity requireOwner() {
        StpUtil.checkLogin();
        StpUtil.checkRole(AuthConstants.OWNER_ROLE);
        long id = StpUtil.getLoginIdAsLong();
        // 角色已由 Sa-Token 根据数据库当前状态校验；这里仅取会话响应所需身份。
        UserAccountEntity account = accounts.selectById(id);
        if (account == null) {
            StpUtil.logout();
            throw new ApiException(ApiErrorCode.FORBIDDEN, MessageConstants.ADMIN_FORBIDDEN);
        }
        return new OwnerIdentity(account.getId(), account.getUsername());
    }
}
