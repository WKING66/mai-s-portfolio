package dev.amai.portfolio.security.context;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.security.SecurityMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;

/** 直接复用 Sa-Token Redis 会话。只提供身份读取，不决定业务权限或查询账号表。 */
public final class AccountContext {
    private static final String ACCOUNT_KEY = "identity";

    private AccountContext() {}

    /** 登录身份只保留调用方真正使用的字段，不保存数据库实体和密码摘要。 */
    public record Account(
            Long id,

            String username,

            List<String> roles) {}

    public static Account currentAccount() {
        long accountId = StpUtil.getLoginIdAsLong();
        var account = account(accountId);
        if (account == null) {
            StpUtil.logout();
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED, SecurityMessageConstants.SESSION_EXPIRED);
        }
        return account;
    }

    public static void set(Account account) {
        StpUtil.getSession().set(ACCOUNT_KEY, account);
    }

    public static List<String> roles(long accountId) {
        var account = account(accountId);
        return account == null ? List.of() : account.roles();
    }

    private static Account account(long accountId) {
        var session = StpUtil.getSessionByLoginId(accountId, false);
        Account account = session == null ? null : session.getModel(ACCOUNT_KEY, Account.class);
        return account != null && account.id() == accountId ? account : null;
    }
}
