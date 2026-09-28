package dev.amai.portfolio.service.impl;

import dev.amai.portfolio.common.AuthConstants;
import dev.amai.portfolio.entity.UserAccountEntity;
import dev.amai.portfolio.enums.AccountStatus;
import dev.amai.portfolio.enums.AccountType;
import dev.amai.portfolio.mapper.UserAccountMapper;
import dev.amai.portfolio.service.OwnerRoleService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OwnerRoleServiceImpl implements OwnerRoleService {
    private final UserAccountMapper accounts;

    public OwnerRoleServiceImpl(UserAccountMapper accounts) {
        this.accounts = accounts;
    }

    @Override
    public List<String> findRoles(long accountId) {
        UserAccountEntity account = accounts.selectById(accountId);
        if (account == null || account.getStatus() != AccountStatus.ENABLED.code()
                || account.getType() != AccountType.OWNER.code()) {
            return List.of();
        }
        return List.of(AuthConstants.OWNER_ROLE);
    }
}
