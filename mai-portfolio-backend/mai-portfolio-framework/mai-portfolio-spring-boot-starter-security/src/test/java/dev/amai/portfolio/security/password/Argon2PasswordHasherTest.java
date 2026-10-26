package dev.amai.portfolio.security.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

class Argon2PasswordHasherTest {
    private final PasswordHasher passwordHasher = new Argon2PasswordHasher();

    @Test
    void preservesFixedCostAndRandomSaltAndRejectsWrongPasswords() {
        String password = "fixture-password";
        String firstHash = passwordHasher.encode(password);
        String secondHash = passwordHasher.encode(password);
        assertThat(firstHash).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
        assertThat(secondHash).isNotEqualTo(firstHash);
        String[] hashComponents = firstHash.split("\\$");
        assertThat(Base64.getDecoder().decode(hashComponents[4])).hasSize(16);
        assertThat(Base64.getDecoder().decode(hashComponents[5])).hasSize(32);
        assertThat(passwordHasher.matches(password, firstHash)).isTrue();
        assertThat(passwordHasher.matches("wrong-password", firstHash)).isFalse();
    }

    @Test
    void matchesExistingArgon2HashesUsingTheirEmbeddedCostParameters() {
        Argon2PasswordEncoder legacyPasswordEncoder =
                new Argon2PasswordEncoder(8, 24, 1, 8 * 1024, 1);
        String legacyHash = legacyPasswordEncoder.encode("fixture-password");
        assertThat(passwordHasher.matches("fixture-password", legacyHash)).isTrue();
        assertThat(passwordHasher.matches("wrong-password", legacyHash)).isFalse();
    }
}
