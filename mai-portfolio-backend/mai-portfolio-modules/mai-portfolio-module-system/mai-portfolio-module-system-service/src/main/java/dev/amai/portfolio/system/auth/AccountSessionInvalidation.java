package dev.amai.portfolio.system.auth;

import dev.amai.portfolio.system.service.AccountAuthenticationService;
import org.springframework.transaction.support.TransactionSynchronization;

/** 仅在账号写事务提交后注销会话；回滚不影响已有登录。 */
public record AccountSessionInvalidation(
        long accountId,

        AccountAuthenticationService accountAuthenticationService) implements TransactionSynchronization {

    @Override
    public void afterCommit() {
        accountAuthenticationService.invalidateSessions(accountId);
    }
}
