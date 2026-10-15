package dev.amai.portfolio.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.mapper.UserAccountMapper;
import dev.amai.portfolio.system.service.AccountAuthenticationService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 每个令牌绑定登录时的凭据版本；数据库版本决定失效，旧记录按已有 TTL 回收。 */
@Service
public class AccountAuthenticationServiceImpl implements AccountAuthenticationService {
    private static final String CREDENTIAL_VERSION = "credentialVersion";
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
    public Optional<UserAccountDO> findCurrentAccount() {
        if (!StpUtil.isLogin()) {
            return Optional.empty();
        }
        return Optional.ofNullable(readSessionAccount(false));
    }

    @Override
    public UserAccountDO currentAccount() {
        return requireAccount(false);
    }

    @Override
    public UserAccountDO lockCurrentAccount() {
        return requireAccount(true);
    }

    private UserAccountDO requireAccount(boolean lock) {
        StpUtil.checkLogin();
        UserAccountDO account = readSessionAccount(lock);
        if (account == null) {
            throw new ApiException(
                    ApiErrorCode.UNAUTHENTICATED, SystemMessageConstants.SESSION_EXPIRED);
        }
        return account;
    }

    private UserAccountDO readSessionAccount(boolean lock) {
        // 1. 从已登录身份读取数据库事实；写入场景由当前事务持有账号行锁。
        long accountId = StpUtil.getLoginIdAsLong();
        UserAccountDO account =
                lock
                        ? userAccountMapper.selectForUpdate(accountId)
                        : userAccountMapper.selectById(accountId);
        // 账号事实已不存在，旧令牌必须撤销；会话查询可投影为匿名，业务入口继续拒绝。
        // 禁用账号仍存在，沿用 403 并允许显式注销；不能与删除混为一谈。
        if (account == null) {
            StpUtil.logout();
            return null;
        }
        // 2. 保留删除与禁用的不同契约，再核对令牌绑定的数据库凭据版本。
        requireEnabled(account);
        var saTokenSession = StpUtil.getTokenSession();
        Object version = saTokenSession == null ? null : saTokenSession.get(CREDENTIAL_VERSION);
        if (!(version instanceof Number number)
                || number.longValue() != account.getCredentialVersion()) {
            // 缺少版本的历史会话也必须重新登录；不依赖失败的 Redis 删除确认失效。
            throw new ApiException(
                    ApiErrorCode.UNAUTHENTICATED, SystemMessageConstants.SESSION_EXPIRED);
        }
        return account;
    }

    @Override
    public String startSession(UserAccountDO account) {
        // 1. 只有启用账号可以建会话；令牌版本使用调用方已读取/锁定的账号事实。
        requireEnabled(account);
        // 每次登录使用独立 token，禁止新登录改写旧 token 的凭据版本。
        StpUtil.login(
                account.getId(),
                new SaLoginParameter().setIsShare(false));
        // 2. 为本次登录生成写操作令牌，并分别保存凭据版本与账号会话 CSRF。
        byte[] bytes = new byte[CSRF_BYTES];
        secureRandom.nextBytes(bytes);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        StpUtil.getTokenSession().set(CREDENTIAL_VERSION, account.getCredentialVersion());
        StpUtil.getSession().set(AuthConstants.CSRF_SESSION_KEY, csrf);
        return csrf;
    }

    private void requireEnabled(UserAccountDO account) {
        if (account == null || account.getStatus() != AccountStatus.ENABLED.code()) {
            throw new ApiException(
                    ApiErrorCode.FORBIDDEN, SystemMessageConstants.ACCOUNT_UNAVAILABLE);
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
}
