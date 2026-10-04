package dev.amai.portfolio;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.tag.api.TaxonomyQueryService;
import dev.amai.portfolio.system.tag.api.TechTagData;
import dev.amai.portfolio.system.tag.entity.domain.TagDO;
import dev.amai.portfolio.system.tag.enums.FeaturedStatus;
import dev.amai.portfolio.system.tag.enums.TagKind;
import dev.amai.portfolio.system.tag.enums.TechGroup;
import dev.amai.portfolio.system.tag.mapper.TagMapper;
import dev.amai.portfolio.system.tag.service.TaxonomyBootstrapService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** 验证标签迁入 system 后，跨模块契约、MyBatis 扫描与初始化事务仍正常。 */
@SpringBootTest
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "PSQL_PASSWORD", matches = ".+")
@Transactional
class TagModuleIntegrationTest extends AuthKeyTestSupport {
    @Autowired TaxonomyQueryService query;
    @Autowired TaxonomyBootstrapService bootstrap;
    @Autowired TagMapper tags;

    @Test
    void featuredQueryFiltersKindAndVisibilityAndKeepsStableOrdering() {
        String prefix = "tag-merge-" + UUID.randomUUID() + "-";
        // 相同分组与排序值按自增 ID 排序；数据库改动随测试事务回滚。
        TagDO first = insert(prefix + "first", TagKind.TECH, FeaturedStatus.FEATURED, TechGroup.TOOL, 100);
        TagDO second = insert(prefix + "second", TagKind.TECH, FeaturedStatus.FEATURED, TechGroup.TOOL, 100);
        TagDO data = insert(prefix + "data", TagKind.TECH, FeaturedStatus.FEATURED, TechGroup.DATA, 0);
        insert(prefix + "hidden", TagKind.TECH, FeaturedStatus.STANDARD, TechGroup.LANGUAGE, 0);
        insert(prefix + "topic", TagKind.TOPIC, FeaturedStatus.FEATURED, null, 0);

        var result = query.featuredTechTags().stream()
            .filter(tag -> tag.slug().startsWith(prefix)).toList();

        assertThat(result).extracting(TechTagData::slug)
            .containsExactly(first.getSlug(), second.getSlug(), data.getSlug());
        assertThat(result).extracting(TechTagData::group).containsExactly("TOOL", "TOOL", "DATA");
        assertThat(result).extracting(TechTagData::logoKey).containsExactly("git", "git", "git");
        assertThat(result.getFirst().name()).isEqualTo(first.getName());
    }

    @Test
    void repeatedBootstrapDoesNotDuplicateOrOverwriteExistingTechTags() {
        bootstrap.initialize();
        long count = tags.selectCount(Wrappers.emptyWrapper());
        TagDO java = tags.selectOne(Wrappers.<TagDO>lambdaQuery().eq(TagDO::getSlug, "tech-java"));
        java.setName("Custom Java");
        java.setNormalizedName("custom java");
        java.setLogoKey("custom-java");
        tags.updateById(java);

        bootstrap.initialize();
        bootstrap.initialize();

        TagDO actual = tags.selectById(java.getId());
        assertThat(tags.selectCount(Wrappers.emptyWrapper())).isEqualTo(count);
        assertThat(actual.getName()).isEqualTo("Custom Java");
        assertThat(actual.getLogoKey()).isEqualTo("custom-java");
    }

    private TagDO insert(String slug, TagKind kind, FeaturedStatus featured, TechGroup group, int order) {
        TagDO tag = new TagDO();
        tag.setKind(kind.code());
        tag.setName(slug);
        tag.setNormalizedName(slug);
        tag.setSlug(slug);
        tag.setGroupCode(group == null ? null : group.code());
        tag.setLogoKey("git");
        tag.setIsFeatured(featured.code());
        tag.setSortOrder(order);
        tags.insert(tag);
        return tag;
    }
}
