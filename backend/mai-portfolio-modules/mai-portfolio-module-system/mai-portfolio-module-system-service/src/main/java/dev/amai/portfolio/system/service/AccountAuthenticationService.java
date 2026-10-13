package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.entity.domain.UserAccountDO;

/** 账号有效性与会话凭据失效的统一业务边界，调用方不依赖会话供应商。 */
public interface AccountAuthenticationService extends dev.amai.portfolio.system.api.AccountIdentityService {
    /** 会话展示入口：匿名或账号已删除返回空；禁用及凭据失效仍按既有错误契约拒绝。 */
    java.util.Optional<UserAccountDO> findCurrentAccount();
    UserAccountDO currentAccount();
    UserAccountDO lockCurrentAccount();
    String startSession(UserAccountDO account);
    String csrfToken();
    long logout();
}
