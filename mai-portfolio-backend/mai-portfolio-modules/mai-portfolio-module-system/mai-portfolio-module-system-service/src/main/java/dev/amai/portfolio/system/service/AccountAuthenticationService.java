package dev.amai.portfolio.system.service;

import dev.amai.portfolio.system.api.AccountIdentityService;
import dev.amai.portfolio.system.entity.domain.UserAccountDO;
import java.util.Optional;

/** 账号有效性与会话凭据失效的统一业务边界，调用方不依赖会话供应商。 */
public interface AccountAuthenticationService
        extends AccountIdentityService {
    /** 会话展示入口：匿名或账号已删除返回空；禁用及凭据失效仍按既有错误契约拒绝。 */
    Optional<UserAccountDO> findCurrentAccount();

    UserAccountDO currentAccount();

    UserAccountDO lockCurrentAccount();

    String startSession(UserAccountDO account);

    String csrfToken();

    long logout();
}
