package dev.amai.portfolio.system.component;

import static org.assertj.core.api.Assertions.*;
import dev.amai.portfolio.web.exception.ApiException;
import org.junit.jupiter.api.Test;

class CredentialPasswordPolicyTest {
    private final dev.amai.portfolio.system.manager.CredentialManager policy =
            dev.amai.portfolio.system.manager.CredentialTestSupport.create(
                    org.mockito.Mockito.mock(dev.amai.portfolio.security.password.PasswordHasher.class));
    @Test void removesOnlyEdgePaddingAndHonorsDefinedLengthBoundaries() {
        assertThat(policy.normalizeNewPassword("\uFEFF\u00A0  abCD_12345!?x\u3000\t")).isEqualTo("abCD_12345!?x");
        assertThat(policy.normalizeNewPassword("a".repeat(12))).hasSize(12);
        assertThat(policy.normalizeNewPassword("a".repeat(190))).hasSize(190);
        assertThatThrownBy(() -> policy.normalizeNewPassword("a".repeat(11))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> policy.normalizeNewPassword("a".repeat(191))).isInstanceOf(ApiException.class);
    }
    @Test void rejectsEmbeddedWhitespaceControlsAndUnsupportedCharacters() {
        for (String value : new String[] {null, "", "abCD 123456789", "abCD\n123456789", "含中文-password-123"}) {
            assertThatThrownBy(() -> policy.normalizeNewPassword(value)).isInstanceOf(ApiException.class);
        }
    }
}
