package dev.amai.portfolio.config;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AuthConstants;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 统一会话 Cookie、CSRF 文档与错误信封；接口保留实际响应码、业务说明和成功类型。 */
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
    /**
     * 所有接口的失败响应沿用统一 R 结构；接口只声明实际状态码和具体业务含义。
     * 不替接口添加错误码，也不改成功泛型、下载文件或业务响应的描述。
     */
    @Bean
    OpenApiCustomizer sharedApiDocumentation() {
        return api -> {
            if (api.getComponents() == null) {
                api.setComponents(new Components());
            }
            var components = api.getComponents();
            var error = ModelConverters.getInstance()
                    .resolveAsResolvedSchema(new AnnotatedType(R.class).resolveAsRef(true));
            error.referencedSchemas.forEach(components::addSchemas);
            components.addParameters(AuthConstants.OPENAPI_CSRF_PARAMETER, new Parameter()
                    .name(AuthConstants.CSRF_HEADER_NAME)
                    .in("header")
                    .required(true)
                    .description("从登录响应或会话查询结果的 csrfToken 获取")
                    .schema(new StringSchema()));
            if (api.getPaths() == null) {
                return;
            }
            api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getResponses() == null) {
                    return;
                }
                operation.getResponses().forEach((status, response) -> {
                    // 错误码保留各接口的声明；仅统一错误信封，避免 SpringDoc 误用成功返回类型。
                    if (status.matches("[45][0-9]{2}") && response.get$ref() == null) {
                        response.setContent(new Content().addMediaType("application/json",
                                new MediaType().schema(new Schema<>().$ref(error.schema.get$ref()))));
                    }
                });
            }));
        };
    }
}
