package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.api.AccountIdentityService;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import java.util.Optional;

/** 登录会话与账号写入入口，调用方通过 Redis 身份上下文读取账号。 */
public interface AccountAuthenticationService
        extends AccountIdentityService {
    /** 匿名返回空；已登录身份从 Redis 会话读取，不查询账号表。 */
    Optional<UserAccountDO> findCurrentAccount();

    UserAccountDO currentAccount();

    UserAccountDO lockCurrentAccount();

    String startSession(UserAccountDO account);

    String csrfToken();

    long logout();

    /** 账号写事务提交后注销该账号的全部会话。 */
    void invalidateSessions(long accountId);
}
