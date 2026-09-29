package dev.amai.portfolio.system.service.impl;

import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.OwnerRoleService;
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
        UserAccountDO account = accounts.selectById(accountId);
        if (account == null || account.getStatus() != AccountStatus.ENABLED.code()
                || account.getType() != AccountType.OWNER.code()) {
            return List.of();
        }
        return List.of(AuthConstants.OWNER_ROLE);
    }
}
