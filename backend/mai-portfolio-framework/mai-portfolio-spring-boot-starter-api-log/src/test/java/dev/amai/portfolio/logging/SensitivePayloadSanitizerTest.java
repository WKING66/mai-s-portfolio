package dev.amai.portfolio.logging;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.logging.autoconfigure.ApiLogProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SensitivePayloadSanitizerTest {
    private final SensitivePayloadSanitizer sanitizer = new SensitivePayloadSanitizer(
        JsonMapper.builder().build(), new ApiLogProperties(true, 2048, List.of("customSecret")));

    @Test
    void redactsNestedBuiltInAndConfiguredFieldsWithoutChangingOriginal() {
        Map<String, Object> input = Map.of("nested", List.of(Map.of(
            "password", "login-password", "csrf_token", "csrf-value",
            "email", "owner@example.test", "customSecret", "custom-value",
            "projectName", "portfolio")));

        String logValue = sanitizer.sanitize("request", input);

        assertThat(logValue).contains("projectName", "portfolio", "[REDACTED]")
            .doesNotContain("login-password", "csrf-value", "owner@example.test", "custom-value");
        assertThat(((Map<?, ?>) ((List<?>) input.get("nested")).getFirst()).get("password"))
            .isEqualTo("login-password");
    }

    @Test
    void redactsLoginPasswordAndSessionTokenAndOmitsBinaryPayload() {
        assertThat(sanitizer.sanitize("input", new LoginPayload("owner", "secret-ciphertext")))
            .contains("owner", "[REDACTED]").doesNotContain("secret-ciphertext");
        assertThat(sanitizer.sanitize("result", new SessionPayload(true, "owner", "csrf-secret")))
            .contains("owner", "[REDACTED]").doesNotContain("csrf-secret");
        assertThat(sanitizer.sanitize("file", new byte[] {1, 2, 3})).isEqualTo("[omitted]");
        assertThat(sanitizer.sanitize("password", "direct-password")).isEqualTo("[REDACTED]");
        assertThat(sanitizer.sanitize("credentials", Map.of(
            "privateKey", "private-key-material", "sessionToken", "full-session-token")))
            .doesNotContain("private-key-material", "full-session-token");
    }

    @Test
    void redactsBothPasswordChangeCiphertexts() {
        String safe = sanitizer.sanitize("request", Map.of(
            "oldEncryptedPassword", "old-password-cipher",
            "newEncryptedPassword", "new-password-cipher"));
        assertThat(safe).contains("oldEncryptedPassword", "newEncryptedPassword", "[REDACTED]")
            .doesNotContain("old-password-cipher", "new-password-cipher");
    }

    @Test
    void capsLongPayloads() {
        SensitivePayloadSanitizer limited = new SensitivePayloadSanitizer(JsonMapper.builder().build(),
            new ApiLogProperties(true, 256, List.of()));
        assertThat(limited.sanitize("note", "x".repeat(1000)))
            .hasSizeLessThan(300).endsWith("...[truncated]");
    }

    private record LoginPayload(String username, String encryptedPassword) {
    }

    private record SessionPayload(boolean loggedIn, String username, String csrfToken) {
    }
}
