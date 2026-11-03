package dev.amai.portfolio.config;

import static org.assertj.core.api.Assertions.assertThat;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.controller.AuthController;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.junit.jupiter.api.Test;

/** 仅验证项目元数据，通用错误文档测试位于 OpenAPI starter。 */
class OpenApiConfigTest {
    @Test
    void definesRequiredCsrfHeaderAndPreservesRepeatableResponseDeclarations() throws Exception {
        var api = new OpenApiConfig().projectApiMetadata();
        var csrf = api.getComponents().getParameters().get(AuthConstants.OPENAPI_CSRF_PARAMETER);
        assertThat(csrf.getName()).isEqualTo(AuthConstants.CSRF_HEADER_NAME);
        assertThat(csrf.getIn()).isEqualTo("header");
        assertThat(csrf.getRequired()).isTrue();
        var operation = AuthController.class.getMethod("logout")
                .getAnnotation(Operation.class);
        assertThat(operation.parameters()[0].ref()).isEqualTo("#/components/parameters/csrfToken");
        var responses = AuthController.class.getMethod("register", String.class, RegisterRequest.class)
                .getAnnotationsByType(ApiResponse.class);
        assertThat(responses).extracting(ApiResponse::responseCode)
                .containsExactly("200", "400", "403", "409", "422", "429");
        assertThat(responses).extracting(ApiResponse::description).doesNotContainNull().doesNotContain("");
    }
}
