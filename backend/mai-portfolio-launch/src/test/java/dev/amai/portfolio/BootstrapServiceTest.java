package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OWNER_PASSWORD", matches = ".+")
class BootstrapServiceTest extends AuthKeyTestSupport {
    @Autowired JdbcTemplate jdbc;

    @Test
    void seedsApprovedIdentityWithoutPlaintextPasswordOrFakeContent() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM site_config", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT display_name FROM site_config", String.class)).isEqualTo("阿霾");
        // 迁移保留历史 pgvector 标签，但只公开展示当前批准的 21 项技术栈。
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag WHERE kind = 1 AND is_featured = 1", Integer.class))
            .isEqualTo(21);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM project", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_post", Integer.class)).isZero();

        String hash = jdbc.queryForObject(
            "SELECT password_hash FROM user_account WHERE username = 'owner' AND type = 0 AND status = 1",
            String.class);
        assertThat(hash).startsWith("$argon2id$");
        assertThat(hash.equals(System.getenv("OWNER_PASSWORD"))).isFalse();
        assertThat(new Argon2PasswordEncoder(16, 32, 1, 19 * 1024, 2)
            .matches(System.getenv("OWNER_PASSWORD"), hash)).isTrue();
    }
}
