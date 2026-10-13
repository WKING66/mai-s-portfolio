package dev.amai.portfolio.system.component;

import static org.assertj.core.api.Assertions.*;
import dev.amai.portfolio.web.exception.ApiException;
import org.junit.jupiter.api.Test;

class NewPasswordPolicyTest {
    private final NewPasswordPolicy policy = new NewPasswordPolicy();
    @Test void removesOnlyEdgePaddingAndHonorsDefinedLengthBoundaries() {
        assertThat(policy.normalize("\uFEFF\u00A0  abCD_12345!?x\u3000\t")).isEqualTo("abCD_12345!?x");
        assertThat(policy.normalize("a".repeat(12))).hasSize(12);
        assertThat(policy.normalize("a".repeat(190))).hasSize(190);
        assertThatThrownBy(() -> policy.normalize("a".repeat(11))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.normalize("a".repeat(191))).isInstanceOf(ApiException.class);
    }
    @Test void rejectsEmbeddedWhitespaceControlsAndUnsupportedCharacters() {
        for (String value : new String[] {null, "", "abCD 123456789", "abCD\n123456789", "含中文-password-123"}) {
            assertThatThrownBy(() -> policy.normalize(value)).isInstanceOf(ApiException.class);
        }
    }
}
