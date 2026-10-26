package dev.amai.portfolio.system.component;

import dev.amai.portfolio.system.exception.InvalidCredentialPayloadException;
import jakarta.validation.Validator;
import java.util.List;
import org.springframework.stereotype.Component;

/** 服务入口复用请求字段约束，不依赖 MVC 的参数绑定异常。 */
@Component
public class AuthRequestValidator {
    private final Validator validator;

    public AuthRequestValidator(Validator validator) {
        this.validator = validator;
    }

    public void validate(Object request) {
        if (request == null) {
            throw new InvalidCredentialPayloadException(List.of());
        }
        List<String> details =
                validator.validate(request).stream()
                        .map(
                                violation ->
                                        violation.getPropertyPath() + ": " + violation.getMessage())
                        .distinct()
                        .sorted()
                        .toList();
        if (!details.isEmpty()) {
            // 只返回字段名与约束消息，绝不携带原始密码/密文等被拒绝的值。
            throw new InvalidCredentialPayloadException(details);
        }
    }
}
