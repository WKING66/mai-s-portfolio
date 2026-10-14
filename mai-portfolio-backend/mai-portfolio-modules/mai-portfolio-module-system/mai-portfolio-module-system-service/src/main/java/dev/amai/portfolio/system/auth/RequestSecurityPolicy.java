package dev.amai.portfolio.system.auth;

import dev.amai.portfolio.system.config.SecurityProperties;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** 只信任容器确认的 HTTPS，不根据客户端转发头自行放行。 */
@Component
public class RequestSecurityPolicy {
    private final SecurityProperties securityProperties;

    public RequestSecurityPolicy(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    public void requireSecureTransport(HttpServletRequest request) {
        if (securityProperties.requireHttps() && !request.isSecure()) {
            throw new ApiException(
                    ApiErrorCode.HTTPS_REQUIRED, SystemMessageConstants.LOGIN_HTTPS_REQUIRED);
        }
    }
}
