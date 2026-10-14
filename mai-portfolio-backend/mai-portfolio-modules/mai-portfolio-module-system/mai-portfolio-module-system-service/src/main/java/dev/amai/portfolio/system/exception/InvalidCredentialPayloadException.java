package dev.amai.portfolio.system.exception;

import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;

/** 认证请求结构不合法；区别于身份验证失败与注册业务政策不满足。 */
public class InvalidCredentialPayloadException extends ApiException {
    private final List<String> details;

    public InvalidCredentialPayloadException(List<String> details) {
        super(ApiErrorCode.AUTH_INVALID_CREDENTIAL_PAYLOAD, SystemMessageConstants.AUTH_REQUEST_INVALID);
        this.details = List.copyOf(details);
    }

    public List<String> details() {
        return details;
    }
}
