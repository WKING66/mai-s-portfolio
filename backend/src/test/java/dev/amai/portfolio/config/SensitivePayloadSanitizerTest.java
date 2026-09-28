package dev.amai.portfolio.config;

import static org.assertj.core.api.Assertions.assertThat;

import dev.amai.portfolio.advice.SensitivePayloadSanitizer;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.dto.LoginRequest;
import dev.amai.portfolio.dto.SessionResponse;
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
        assertThat(sanitizer.sanitize("input", new LoginRequest("owner", "challenge-id", "secret-ciphertext")))
            .contains("owner", "[REDACTED]").doesNotContain("secret-ciphertext", "challenge-id");
        assertThat(sanitizer.sanitize("result", R.success(new SessionResponse(true, "owner", "csrf-secret"))))
            .contains("owner", "[REDACTED]").doesNotContain("csrf-secret");
        assertThat(sanitizer.sanitize("file", new byte[] {1, 2, 3})).isEqualTo("[omitted]");
        assertThat(sanitizer.sanitize("password", "direct-password")).isEqualTo("[REDACTED]");
        assertThat(sanitizer.sanitize("challenge", Map.of(
            "challengeId", "one-time-id", "publicKey", "base64-public-key")))
            .doesNotContain("one-time-id", "base64-public-key");
    }

    @Test
    void capsLongPayloads() {
        SensitivePayloadSanitizer limited = new SensitivePayloadSanitizer(JsonMapper.builder().build(),
            new ApiLogProperties(true, 256, List.of()));
        assertThat(limited.sanitize("note", "x".repeat(1000)))
            .hasSizeLessThan(300).endsWith("...[truncated]");
    }
}
