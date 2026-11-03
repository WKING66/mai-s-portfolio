package dev.amai.portfolio.config;

import dev.amai.portfolio.system.api.AuthConstants;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 仅定义本项目名称、版本、会话 Cookie 和 CSRF 元数据；通用文档能力由 framework 提供。 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "阿霾个人作品集 API",
                version = "v1",
                description = "公开资料无需登录；账号共用登录会话 Cookie，管理接口还要求 OWNER 角色，写操作还需 CSRF 令牌。"))
@SecurityScheme(
        name = AuthConstants.OPENAPI_SESSION_SCHEME,
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.COOKIE,
        paramName = AuthConstants.SESSION_COOKIE_NAME,
        description = "由通用登录接口设置的 HttpOnly Cookie；不需要也不能在请求体中传递。")
public class OpenApiConfig {
    /** 仅声明本项目 CSRF 头元数据；通用错误文档由 framework OpenAPI starter 自动装配。 */
    @Bean
    OpenAPI projectApiMetadata() {
        return new OpenAPI().components(new Components()
                .addParameters(AuthConstants.OPENAPI_CSRF_PARAMETER, new Parameter()
                        .name(AuthConstants.CSRF_HEADER_NAME)
                        .in("header")
                        .required(true)
                        .description("从登录响应或会话查询结果的 csrfToken 获取")
                        .schema(new StringSchema())));
    }
}
