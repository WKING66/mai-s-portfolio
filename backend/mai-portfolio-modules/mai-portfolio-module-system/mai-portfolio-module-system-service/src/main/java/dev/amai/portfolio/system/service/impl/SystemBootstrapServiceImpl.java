package dev.amai.portfolio.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.config.BootstrapProperties;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.SystemBootstrapService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SystemBootstrapServiceImpl implements SystemBootstrapService {
    private static final String OWNER_USERNAME = "owner";
    private static final int MIN_OWNER_PASSWORD_LENGTH = 12;

    private final UserAccountMapper accounts;
    private final PasswordHasher passwords;
    private final BootstrapProperties settings;

    public SystemBootstrapServiceImpl(UserAccountMapper accounts,
            PasswordHasher passwords, BootstrapProperties settings) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.settings = settings;
    }

    @Override
    @Transactional
    public void initialize() {
        accounts.acquireBootstrapLock();
        // 只初始化账号域数据；个人资料和技术标签由各自业务模块负责。
        boolean needsOwner = accounts.selectCount(Wrappers.<UserAccountDO>lambdaQuery()
            .apply("LOWER(username) = {0}", OWNER_USERNAME)) == 0;
        if (needsOwner && (settings.initialPassword() == null
                || settings.initialPassword().length() < MIN_OWNER_PASSWORD_LENGTH)) {
            throw new IllegalStateException(SystemMessageConstants.OWNER_PASSWORD_TOO_SHORT);
        }
        if (!needsOwner) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserAccountDO owner = new UserAccountDO();
        owner.setUsername(OWNER_USERNAME);
        owner.setType(AccountType.OWNER.code());
        owner.setPasswordHash(passwords.encode(settings.initialPassword()));
        owner.setStatus(AccountStatus.ENABLED.code());
        owner.setCreatedAt(now);
        owner.setUpdatedAt(now);
        accounts.insert(owner);
    }
}
