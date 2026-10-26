package dev.amai.portfolio.logging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.logging.autoconfigure.ApiLogProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SensitivePayloadSanitizerTest {
    private final SensitivePayloadSanitizer sensitivePayloadSanitizer =
            new SensitivePayloadSanitizer(
                    JsonMapper.builder().build(),
                    new ApiLogProperties(true, 2048, List.of("customSecret")));

    @Test
    void redactsNestedBuiltInAndConfiguredFieldsWithoutChangingOriginal() {
        Map<String, Object> input =
                Map.of(
                        "nested",
                        List.of(
                                Map.of(
                                        "password",
                                        "login-password",
                                        "csrf_token",
                                        "csrf-value",
                                        "email",
                                        "owner@example.test",
                                        "customSecret",
                                        "custom-value",
                                        "projectName",
                                        "portfolio")));

        String logValue = sensitivePayloadSanitizer.sanitize("request", input);

        assertThat(logValue)
                .contains("projectName", "portfolio", "[REDACTED]")
                .doesNotContain(
                        "login-password", "csrf-value", "owner@example.test", "custom-value");
        assertThat(((Map<?, ?>) ((List<?>) input.get("nested")).getFirst()).get("password"))
                .isEqualTo("login-password");
    }

    @Test
    void redactsLoginPasswordAndSessionTokenAndOmitsBinaryPayload() {
        assertThat(
                        sensitivePayloadSanitizer.sanitize(
                                "input", new LoginPayload("owner", "secret-ciphertext")))
                .contains("owner", "[REDACTED]")
                .doesNotContain("secret-ciphertext");
        assertThat(
                        sensitivePayloadSanitizer.sanitize(
                                "result", new SessionPayload(true, "owner", "csrf-secret")))
                .contains("owner", "[REDACTED]")
                .doesNotContain("csrf-secret");
        assertThat(sensitivePayloadSanitizer.sanitize("file", new byte[] {1, 2, 3}))
                .isEqualTo("[omitted]");
        assertThat(sensitivePayloadSanitizer.sanitize("password", "direct-password"))
                .isEqualTo("[REDACTED]");
        assertThat(
                        sensitivePayloadSanitizer.sanitize(
                                "credentials",
                                Map.of(
                                        "privateKey",
                                        "private-key-material",
                                        "sessionToken",
                                        "full-session-token")))
                .doesNotContain("private-key-material", "full-session-token");
    }

    @Test
    void redactsBothPasswordChangeCiphertexts() {
        String safe =
                sensitivePayloadSanitizer.sanitize(
                        "request",
                        Map.of(
                                "oldEncryptedPassword", "old-password-cipher",
                                "newEncryptedPassword", "new-password-cipher"));
        assertThat(safe)
                .contains("oldEncryptedPassword", "newEncryptedPassword", "[REDACTED]")
                .doesNotContain("old-password-cipher", "new-password-cipher");
    }

    @Test
    void capsLongPayloads() {
        SensitivePayloadSanitizer limitedPayloadSanitizer =
                new SensitivePayloadSanitizer(
                        JsonMapper.builder().build(), new ApiLogProperties(true, 256, List.of()));
        assertThat(limitedPayloadSanitizer.sanitize("note", "x".repeat(1000)))
                .hasSizeLessThan(300)
                .endsWith("...[truncated]");
    }

    @Test
    void redactsSignedUrlsInObjectsArraysAndRootWithoutMutatingOrdinaryLinks() {
        for (String key :
                List.of(
                        "Signature",
                        "OSSAccessKeyId",
                        "x-oss-signature",
                        "x-oss-credential",
                        "security-token")) {
            String signed = "https://oss.test/file?" + key + "=bearer-secret";
            Map<String, Object> input =
                    Map.of(
                            "resourceUrl",
                            signed,
                            "links",
                            List.of(signed, "https://github.com/me"));
            assertThat(sensitivePayloadSanitizer.sanitize("result", input))
                    .doesNotContain("bearer-secret")
                    .contains("github.com/me", "[REDACTED]");
            assertThat(sensitivePayloadSanitizer.sanitize("url", signed)).isEqualTo("[REDACTED]");
            assertThat(input.get("resourceUrl")).isEqualTo(signed);
        }
    }

    @Test
    void omitsSerializationFailuresInsteadOfReturningOriginalValue() {
        assertThat(sensitivePayloadSanitizer.sanitize("request", new UnserializablePayload()))
                .isEqualTo("[omitted]");
    }

    @Test
    void omitsOverDepthChildrenWithoutMutatingOriginalTree() {
        Object originalPayload = java.util.Map.of("note", "deep-value");
        for (int depth = 0; depth < 40; depth++) {
            originalPayload = java.util.Map.of("child", originalPayload);
        }
        String originalJson = JsonMapper.builder().build().writeValueAsString(originalPayload);

        String logValue = sensitivePayloadSanitizer.sanitize("request", originalPayload);

        assertThat(logValue).contains("[omitted]").doesNotContain("deep-value");
        assertThat(JsonMapper.builder().build().writeValueAsString(originalPayload))
                .isEqualTo(originalJson);
    }

    static class UnserializablePayload {
        public String getValue() {
            throw new IllegalStateException("serialization failure");
        }
    }

    private record LoginPayload(String username, String encryptedPassword) {}

    private record SessionPayload(boolean loggedIn, String username, String csrfToken) {}
}
