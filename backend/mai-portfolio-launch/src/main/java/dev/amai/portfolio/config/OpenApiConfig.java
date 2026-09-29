package dev.amai.portfolio.config;

import dev.amai.portfolio.system.api.AuthConstants;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
    title = "阿霾个人作品集 API",
    version = "v1",
    description = "公开资料无需登录；管理接口使用站长会话 Cookie，写操作还需 CSRF 令牌。"
))
@SecurityScheme(
    name = AuthConstants.OPENAPI_OWNER_SESSION_SCHEME,
    type = SecuritySchemeType.APIKEY,
    in = SecuritySchemeIn.COOKIE,
    paramName = AuthConstants.SESSION_COOKIE_NAME,
    description = "由站长登录接口设置的 HttpOnly Cookie；不需要也不能在请求体中传递。"
)
public class OpenApiConfig {
}
