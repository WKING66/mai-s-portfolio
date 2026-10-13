package dev.amai.portfolio.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.api.OwnerAuthorizationService;
import dev.amai.portfolio.system.auth.RequestSecurityPolicy;
import dev.amai.portfolio.system.service.AccountAuthenticationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/** 不识别调用业务的路径或查询参数；是否需要 OWNER 由调用业务决定。 */
@Service
public class OwnerAuthorizationServiceImpl implements OwnerAuthorizationService {
    private final AccountAuthenticationService authentication;
    private final RequestSecurityPolicy transport;
    private final HttpServletRequest request;
    public OwnerAuthorizationServiceImpl(AccountAuthenticationService authentication,
            RequestSecurityPolicy transport, HttpServletRequest request) {
        this.authentication = authentication;
        this.transport = transport;
        this.request = request;
    }
    @Override public void requireOwner() {
        transport.requireSecureTransport(request);
        authentication.currentAccount();
        StpUtil.checkRole(AuthConstants.OWNER_ROLE);
    }
}
