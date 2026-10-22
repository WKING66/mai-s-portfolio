package dev.amai.portfolio.system.auth;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.convert.AccountConvert;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import dev.amai.portfolio.system.enums.AccountStatus;
import dev.amai.portfolio.system.enums.AccountType;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;

/** 身份上下文复用 Sa-Token 的 Redis 账号会话及其 TTL，不在普通请求中查询账号表。 */
public final class AccountContext {
    private static final String ACCOUNT_KEY = "account";

    private AccountContext() {}

    public static UserAccountDO currentAccount() {
        long accountId = StpUtil.getLoginIdAsLong();
        var account = account(accountId);
        if (account == null) {
            // 旧会话没有身份快照时要求重新登录，不增加查库兼容分支。
            StpUtil.logout();
            throw new ApiException(ApiErrorCode.UNAUTHENTICATED, SystemMessageConstants.SESSION_EXPIRED);
        }
        if (account.getStatus() != AccountStatus.ENABLED.code()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, SystemMessageConstants.ACCOUNT_UNAVAILABLE);
        }
        return account;
    }

    public static void set(UserAccountDO account) {
        // 只保存 ID、用户名、账号类型及状态；不复制密码摘要、凭据版本及数据库审计值。
        StpUtil.getSession().set(ACCOUNT_KEY, AccountConvert.MAPPER.toSession(account));
    }

    public static List<String> roles(long accountId) {
        return roles(account(accountId));
    }

    public static List<String> roles(UserAccountDO account) {
        return account != null && account.getStatus() == AccountStatus.ENABLED.code()
                && account.getType() == AccountType.OWNER.code()
                ? List.of(AuthConstants.OWNER_ROLE) : List.of();
    }

    private static UserAccountDO account(long accountId) {
        var session = StpUtil.getSessionByLoginId(accountId, false);
        UserAccountDO account = session == null ? null : session.getModel(ACCOUNT_KEY, UserAccountDO.class);
        return account != null && account.getId() == accountId ? account : null;
    }
}
