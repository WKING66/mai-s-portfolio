package dev.amai.portfolio.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.auth.AccountContext;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.AccountAuthenticationService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 登录建立 Redis 身份上下文；普通读请求复用会话，账号写操作才读取并锁定账号表。 */
@Service
public class AccountAuthenticationServiceImpl implements AccountAuthenticationService {
    private static final int CSRF_BYTES = 32;

    private final UserAccountMapper userAccountMapper;

    private final SecureRandom secureRandom = new SecureRandom();

    public AccountAuthenticationServiceImpl(UserAccountMapper userAccountMapper) {
        this.userAccountMapper = userAccountMapper;
    }

    @Override
    public long currentAccountId() {
        return currentAccount().getId();
    }

    @Override
    public List<String> currentRoles() {
        return AccountContext.roles(currentAccount());
    }

    @Override
    public Optional<UserAccountDO> findCurrentAccount() {
        return StpUtil.isLogin() ? Optional.of(currentAccount()) : Optional.empty();
    }

    @Override
    public UserAccountDO currentAccount() {
        return AccountContext.currentAccount();
    }

    @Override
    public UserAccountDO lockCurrentAccount() {
        // 账号写入需要数据库中的完整账号及密码摘要；此查询不是普通接口的权限检查。
        UserAccountDO account = userAccountMapper.selectForUpdate(currentAccountId());
        if (account == null) {
            StpUtil.logout();
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED, SystemMessageConstants.SESSION_EXPIRED);
        }
        requireEnabled(account);
        return account;
    }

    @Override
    public String startSession(UserAccountDO account) {
        requireEnabled(account);
        StpUtil.login(account.getId(), new SaLoginParameter().setIsShare(false));
        AccountContext.set(account);
        byte[] bytes = new byte[CSRF_BYTES];
        secureRandom.nextBytes(bytes);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StpUtil.getSession().set(AuthConstants.CSRF_SESSION_KEY, csrf);
        return csrf;
    }

    private void requireEnabled(UserAccountDO account) {
        if (account == null || account.getStatus() != AccountStatus.ENABLED.code()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ACCOUNT_UNAVAILABLE);
        }
    }

    @Override
    public String csrfToken() {
        return (String) StpUtil.getSession().get(AuthConstants.CSRF_SESSION_KEY);
    }

    @Override
    public long logout() {
        long accountId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        return accountId;
    }

    @Override
    public void invalidateSessions(long accountId) {
        StpUtil.logout(accountId);
    }
}
