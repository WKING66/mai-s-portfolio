package dev.amai.portfolio.openapi;

import dev.amai.portfolio.common.R;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** 通用 OpenAPI 文档装配，不依赖业务模块或项目认证常量。 */
@AutoConfiguration
public class OpenApiAutoConfiguration {
    /**
     * 所有接口的失败响应沿用统一 R 结构；接口只声明实际状态码和具体业务含义。
     * 不替接口添加错误码，也不改成功泛型、下载文件或业务响应的描述。
     */
    @Bean
    @ConditionalOnMissingBean(name = "sharedApiDocumentation")
    OpenApiCustomizer sharedApiDocumentation() {
        return api -> {
            if (api.getComponents() == null) {
                api.setComponents(new Components());
            }
            var components = api.getComponents();
            var error = ModelConverters.getInstance()
                    .resolveAsResolvedSchema(new AnnotatedType(R.class).resolveAsRef(true));
            error.referencedSchemas.forEach(components::addSchemas);
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
