package dev.amai.portfolio.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.security.password.PasswordHasher;
import dev.amai.portfolio.system.config.BootstrapProperties;
import dev.amai.portfolio.system.constant.PasswordCipherConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
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

    private final UserAccountMapper userAccountMapper;
    private final PasswordHasher passwordHasher;
    private final BootstrapProperties bootstrapProperties;

    public SystemBootstrapServiceImpl(
            UserAccountMapper userAccountMapper,
            PasswordHasher passwordHasher,
            BootstrapProperties bootstrapProperties) {
        this.userAccountMapper = userAccountMapper;
        this.passwordHasher = passwordHasher;
        this.bootstrapProperties = bootstrapProperties;
    }

    @Override
    @Transactional
    public void initialize() {
        // 1. 事务级数据库锁串行化多实例初始化，事务结束自动释放。
        userAccountMapper.acquireBootstrapLock();
        // 只初始化账号域数据；个人资料和技术标签由各自业务模块负责。
        boolean needsOwner =
                userAccountMapper.selectCount(
                                Wrappers.<UserAccountDO>lambdaQuery()
                                        .apply("LOWER(username) = {0}", OWNER_USERNAME))
                        == 0;
        // 2. 仅首次创建检查初始化密码；已有站长不会被启动配置覆盖。
        if (needsOwner
                && (bootstrapProperties.initialPassword() == null
                        || bootstrapProperties.initialPassword().length()
                                < MIN_OWNER_PASSWORD_LENGTH)) {
            throw new IllegalStateException(SystemMessageConstants.OWNER_PASSWORD_TOO_SHORT);
        }
        if (!needsOwner) {
            return;
        }
        if (bootstrapProperties
                        .initialPassword()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        .length
                > PasswordCipherConstants.MAX_PLAINTEXT_BYTES) {
            throw new IllegalStateException(SystemMessageConstants.OWNER_PASSWORD_TOO_LONG);
        }
        // 3. 只创建缺失站长账号并保存密码哈希，其他业务资料由对应模块初始化。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserAccountDO owner = new UserAccountDO();
        owner.setUsername(OWNER_USERNAME);
        owner.setType(AccountType.OWNER.code());
        owner.setPasswordHash(passwordHasher.encode(bootstrapProperties.initialPassword()));
        owner.setStatus(AccountStatus.ENABLED.code());
        owner.setCreatedAt(now);
        owner.setUpdatedAt(now);
        userAccountMapper.insert(owner);
    }
}
