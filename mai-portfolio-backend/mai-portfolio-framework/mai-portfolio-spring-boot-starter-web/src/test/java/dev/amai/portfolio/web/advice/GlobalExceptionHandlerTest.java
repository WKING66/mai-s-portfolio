package dev.amai.portfolio.web.advice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {
    private static final long TEST_UPLOAD_LIMIT_BYTES = 128L * 1024 * 1024;

    private final MockMvc mockMvc =
            MockMvcBuilders.standaloneSetup(new BindingController())
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .build();

    @Test
    void invalidPathParameterReturnsBadRequestInsteadOfInternalError() throws Exception {
        mockMvc.perform(get("/probe/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unsupportedMethodPreservesAllowHeaderAndStatus() throws Exception {
        mockMvc.perform(post("/probe/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedContentTypeReturnsClientError() throws Exception {
        mockMvc.perform(post("/probe").contentType(MediaType.TEXT_PLAIN).content("invalid"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void businessCredentialsRejectionRetainsItsOwnStatusAndCode() throws Exception {
        mockMvc.perform(get("/probe/denied"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("credentials rejected"))
                .andExpect(jsonPath("$.details").isEmpty());
    }

    @Test
    void dtoValidationReturnsSortedFieldDetailsSeparatelyFromBusinessRejection() throws Exception {
        mockMvc.perform(
                        post("/probe/validated")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"\",\"email\":\"\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("email: required"))
                .andExpect(jsonPath("$.details[1]").value("username: required"));
    }

    @RestController
    static class BindingController {
        @GetMapping("/probe/{id}")
        Long read(@PathVariable Long id) {
            return id;
        }

        @GetMapping("/probe/denied")
        Long rejectCredentials() {
            throw new ApiException(ApiErrorCode.AUTH_INVALID_CREDENTIALS, "credentials rejected");
        }

        @PostMapping("/probe/validated")
        void validate(@Valid @RequestBody ValidatedInput input) {}

        @PostMapping(value = "/probe", consumes = MediaType.APPLICATION_JSON_VALUE)
        String write(@RequestBody String body) {
            return body;
        }
    }

    record ValidatedInput(
            @NotBlank(message = "required") String username,
            @NotBlank(message = "required") String email) {}

    @Test
    void multipartParserLimitHasExplicit413Envelope() {
        var response =
                new GlobalExceptionHandler()
                        .handlePayloadTooLarge(
                                new org.springframework.web.multipart
                                        .MaxUploadSizeExceededException(TEST_UPLOAD_LIMIT_BYTES));
        org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value()).isEqualTo(413);
        org.assertj.core.api.Assertions.assertThat(response.getBody().code())
                .isEqualTo("PAYLOAD_TOO_LARGE");
    }
}
