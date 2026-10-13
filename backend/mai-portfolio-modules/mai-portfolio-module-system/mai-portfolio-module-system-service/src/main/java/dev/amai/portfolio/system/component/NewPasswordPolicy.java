package dev.amai.portfolio.system.component;

import dev.amai.portfolio.system.constant.PasswordCipherConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 注册及改密共用的新密码政策；旧密码登录与初始化 OWNER 不做该归一化。 */
@Component
public class NewPasswordPolicy {
    private static final int MIN_CHARACTERS = 12;
    private static final Pattern PADDING = Pattern.compile("^[\\s\\p{Z}\\uFEFF]+|[\\s\\p{Z}\\uFEFF]+$");
    private static final Pattern CHARACTERS = Pattern.compile("[\\x21-\\x7E]+");

    public String normalize(String value) {
        String password = value == null ? "" : PADDING.matcher(value).replaceAll("");
        if (!password.isEmpty() && !CHARACTERS.matcher(password).matches()) {
            throw invalid(SystemMessageConstants.REGISTER_PASSWORD_CHARACTERS_INVALID);
        }
        if (password.codePointCount(0, password.length()) < MIN_CHARACTERS
                || password.getBytes(StandardCharsets.UTF_8).length > PasswordCipherConstants.MAX_PLAINTEXT_BYTES) {
            throw invalid(SystemMessageConstants.REGISTER_PASSWORD_INVALID);
        }
        return password;
    }

    private ApiException invalid(String message) {
        return new ApiException(ApiErrorCode.VALIDATION_FAILED, message);
    }
}
