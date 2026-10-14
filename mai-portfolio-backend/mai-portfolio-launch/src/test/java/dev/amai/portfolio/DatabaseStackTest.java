package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.druid.pool.DruidDataSource;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.system.enums.FeaturedStatus;
import dev.amai.portfolio.system.enums.TagKind;
import dev.amai.portfolio.system.enums.TechGroup;
import dev.amai.portfolio.system.mapper.TagMapper;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@Transactional
class DatabaseStackTest extends AuthKeyTestSupport {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired SmokeTagMapper mapper;
    @Autowired TagMapper tags;

    @Test
    void druidAndMybatisPlusPaginateAgainstLocalPostgresql() {
        assertThat(dataSource).isInstanceOf(DruidDataSource.class);
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                INSERT INTO tag (kind, name, normalized_name, slug, group_code, logo_key, is_featured, sort_order)
                VALUES (1, ?, ?, ?, 0, NULL, 0, 0)
                """, "smoke-" + i, "smoke-test-" + i, "smoke-test-" + i);
        }
        Page<Map<String, Object>> page = new Page<>(2, 1);
        var result = mapper.findSmokeTags(page);
        assertThat(result.getTotal()).isEqualTo(3);
        assertThat(result.getRecords()).hasSize(1);
    }

    @Test
    void mybatisPlusGeneratedIdDoesNotCollideWithMigratedTags() {
        Long greatestMigratedId = jdbc.queryForObject("SELECT max(id) FROM tag", Long.class);
        TagDO tag = new TagDO();
        tag.setKind(TagKind.TECH.code());
        tag.setName("Sequence smoke test");
        tag.setNormalizedName("sequence smoke test");
        tag.setSlug("sequence-smoke-test");
        tag.setGroupCode(TechGroup.TOOL.code());
        tag.setIsFeatured(FeaturedStatus.STANDARD.code());
        tag.setSortOrder(999);
        assertThat(tags.insert(tag)).isEqualTo(1);
        assertThat(tag.getId()).isGreaterThan(greatestMigratedId);
    }
}
