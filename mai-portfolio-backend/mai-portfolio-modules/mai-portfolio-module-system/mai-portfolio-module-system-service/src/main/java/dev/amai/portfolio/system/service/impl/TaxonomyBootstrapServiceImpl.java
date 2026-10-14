package dev.amai.portfolio.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.system.entity.domain.TagDO;
import dev.amai.portfolio.system.enums.FeaturedStatus;
import dev.amai.portfolio.system.enums.TagKind;
import dev.amai.portfolio.system.enums.TechGroup;
import dev.amai.portfolio.system.mapper.TagMapper;
import dev.amai.portfolio.system.service.TaxonomyBootstrapService;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaxonomyBootstrapServiceImpl implements TaxonomyBootstrapService {
    private final TagMapper tags;

    public TaxonomyBootstrapServiceImpl(TagMapper tags) {
        this.tags = tags;
    }

    @Override
    @Transactional
    public void initialize() {
        tags.acquireBootstrapLock();
        Set<String> existingSlugs = tags.selectList(Wrappers.<TagDO>lambdaQuery()
                .eq(TagDO::getKind, TagKind.TECH.code()))
            .stream().map(TagDO::getSlug).collect(Collectors.toSet());
        for (Tech tech : TECH_STACK) {
            String slug = "tech-" + tech.slug();
            if (existingSlugs.contains(slug)) {
                continue;
            }
            TagDO tag = new TagDO();
            tag.setKind(TagKind.TECH.code());
            tag.setName(tech.name());
            tag.setNormalizedName(tech.name().toLowerCase(Locale.ROOT));
            tag.setSlug(slug);
            tag.setGroupCode(tech.group().code());
            tag.setIsFeatured(FeaturedStatus.FEATURED.code());
            tag.setSortOrder(tech.order());
            tags.insert(tag);
        }
    }

    private record Tech(String slug, String name, TechGroup group, int order) {
    }

    private static final List<Tech> TECH_STACK = List.of(
        new Tech("java", "Java", TechGroup.LANGUAGE, 0),
        new Tech("javascript", "JavaScript", TechGroup.LANGUAGE, 1),
        new Tech("typescript", "TypeScript", TechGroup.LANGUAGE, 2),
        new Tech("python", "Python", TechGroup.LANGUAGE, 3),
        new Tech("spring-boot", "Spring Boot", TechGroup.FRAMEWORK, 0),
        new Tech("vue", "Vue", TechGroup.FRAMEWORK, 1),
        new Tech("vite", "Vite", TechGroup.FRAMEWORK, 2),
        new Tech("langchain", "LangChain", TechGroup.FRAMEWORK, 3),
        new Tech("langgraph", "LangGraph", TechGroup.FRAMEWORK, 4),
        new Tech("mybatis-plus", "MyBatis-Plus", TechGroup.FRAMEWORK, 5),
        new Tech("git", "Git", TechGroup.TOOL, 0),
        new Tech("jenkins", "Jenkins", TechGroup.TOOL, 1),
        new Tech("github-ci-cd", "GitHub CI/CD", TechGroup.TOOL, 2),
        new Tech("linux", "Linux", TechGroup.INFRA, 0),
        new Tech("docker", "Docker", TechGroup.INFRA, 1),
        new Tech("nginx", "Nginx", TechGroup.INFRA, 2),
        new Tech("mysql", "MySQL", TechGroup.DATA, 0),
        new Tech("elasticsearch", "Elasticsearch", TechGroup.DATA, 1),
        new Tech("redis", "Redis", TechGroup.DATA, 2),
        new Tech("postgresql", "PostgreSQL", TechGroup.DATA, 3),
        new Tech("milvus", "Milvus", TechGroup.DATA, 4)
    );
}
