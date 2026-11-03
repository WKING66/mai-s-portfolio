package dev.amai.portfolio.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.controller.AuthController;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
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

/** 纯文档配置单元测试，不启动 Spring Boot、数据库、Redis 或 OSS。 */
class OpenApiConfigTest {
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

        new OpenApiConfig().sharedApiDocumentation().customise(api);

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
        new OpenApiConfig().sharedApiDocumentation().customise(api);
        assertThat(download.getContent()).isSameAs(downloadContent);
        assertThat(explicit.getContent()).isNull();
        assertThat(explicit.get$ref()).isEqualTo("#/components/responses/Existing");
    }

    @Test
    void definesRequiredCsrfHeaderAndPreservesRepeatableResponseDeclarations() throws Exception {
        var api = new OpenAPI();
        new OpenApiConfig().sharedApiDocumentation().customise(api);
        var csrf = api.getComponents().getParameters().get(AuthConstants.OPENAPI_CSRF_PARAMETER);
        assertThat(csrf.getName()).isEqualTo(AuthConstants.CSRF_HEADER_NAME);
        assertThat(csrf.getIn()).isEqualTo("header");
        assertThat(csrf.getRequired()).isTrue();
        var operation = AuthController.class.getMethod("logout")
                .getAnnotation(io.swagger.v3.oas.annotations.Operation.class);
        assertThat(operation.parameters()[0].ref()).isEqualTo("#/components/parameters/csrfToken");
        var responses = AuthController.class.getMethod("register", String.class, RegisterRequest.class)
                .getAnnotationsByType(io.swagger.v3.oas.annotations.responses.ApiResponse.class);
        assertThat(responses).extracting(io.swagger.v3.oas.annotations.responses.ApiResponse::responseCode)
                .containsExactly("200", "400", "403", "409", "422", "429");
        assertThat(responses).extracting(io.swagger.v3.oas.annotations.responses.ApiResponse::description).doesNotContainNull().doesNotContain("");
    }
}
