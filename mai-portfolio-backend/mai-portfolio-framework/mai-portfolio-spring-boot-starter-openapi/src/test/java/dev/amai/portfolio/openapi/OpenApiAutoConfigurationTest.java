package dev.amai.portfolio.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 纯文档配置单元测试，不启动 Spring Boot、数据库、Redis 或 OSS。 */
class OpenApiAutoConfigurationTest {
    @Test
    void sharesErrorSchemaWithoutChangingDescriptionsOrSuccessTypes() {
        var successContent = new Content().addMediaType("application/json",
                new MediaType().schema(new StringSchema()));
        var success = new ApiResponse().description("创建成功")
                .content(successContent);
        var badRequest = new ApiResponse().description("密文格式无效");
        var conflict = new ApiResponse().description("用户名已使用");
        var responses = new ApiResponses().addApiResponse("200", success)
                .addApiResponse("400", badRequest).addApiResponse("409", conflict);
        var api = new OpenAPI().components(new Components()).paths(new Paths()
                .addPathItem("/register", new PathItem().post(new Operation().responses(responses))));

        new OpenApiAutoConfiguration().sharedApiDocumentation().customise(api);

        assertThat(responses).containsOnlyKeys("200", "400", "409");
        assertThat(success.getContent()).isSameAs(successContent);
        assertThat(badRequest.getDescription()).isEqualTo("密文格式无效");
        assertThat(conflict.getDescription()).isEqualTo("用户名已使用");
        String reference = badRequest.getContent().get("application/json").getSchema().get$ref();
        assertThat(reference).startsWith("#/components/schemas/");
        assertThat(conflict.getContent().get("application/json").getSchema().get$ref()).isEqualTo(reference);
        var errorSchema = api.getComponents().getSchemas().get(reference.substring(reference.lastIndexOf('/') + 1));
        assertThat(errorSchema.getProperties()).containsKeys("code", "message", "data", "details");
    }

    @Test
    void keepsBinaryDownloadsAndExplicitResponseReferences() {
        var downloadContent = new Content().addMediaType("application/zip",
                new MediaType().schema(new StringSchema().format("binary")));
        var download = new ApiResponse().content(downloadContent);
        var explicit = new ApiResponse().$ref("#/components/responses/Existing");
        var operation = new Operation().responses(new ApiResponses().addApiResponse("200", download)
                .addApiResponse("403", explicit));
        var api = new OpenAPI().paths(new Paths().addPathItem("/export", new PathItem().get(operation)));
        new OpenApiAutoConfiguration().sharedApiDocumentation().customise(api);
        assertThat(download.getContent()).isSameAs(downloadContent);
        assertThat(explicit.getContent()).isNull();
        assertThat(explicit.get$ref()).isEqualTo("#/components/responses/Existing");
    }

    @Test
    void registersGenericCustomizerWithoutBusinessModulesAndAllowsOverride() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations
                        .of(OpenApiAutoConfiguration.class));
        runner.run(context -> assertThat(context).hasNotFailed()
                .hasSingleBean(OpenApiCustomizer.class));
        var replacement = mock(OpenApiCustomizer.class);
        runner.withBean("sharedApiDocumentation", OpenApiCustomizer.class,
                        () -> replacement)
                .run(context -> assertThat(context.getBean("sharedApiDocumentation")).isSameAs(replacement));
    }
}
