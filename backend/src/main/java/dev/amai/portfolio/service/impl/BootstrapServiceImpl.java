package dev.amai.portfolio.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.common.MessageConstants;
import dev.amai.portfolio.config.BootstrapProperties;
import dev.amai.portfolio.entity.SiteConfigEntity;
import dev.amai.portfolio.entity.TagEntity;
import dev.amai.portfolio.entity.UserAccountEntity;
import dev.amai.portfolio.enums.AccountStatus;
import dev.amai.portfolio.enums.AccountType;
import dev.amai.portfolio.enums.FeaturedStatus;
import dev.amai.portfolio.enums.TagKind;
import dev.amai.portfolio.enums.TechGroup;
import dev.amai.portfolio.mapper.SiteConfigMapper;
import dev.amai.portfolio.mapper.TagMapper;
import dev.amai.portfolio.mapper.UserAccountMapper;
import dev.amai.portfolio.service.BootstrapService;
import dev.amai.portfolio.service.PasswordService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapServiceImpl implements BootstrapService {
    private static final String OWNER_USERNAME = "owner";
    private static final int MIN_OWNER_PASSWORD_LENGTH = 12;

    private final SiteConfigMapper siteConfigs;
    private final UserAccountMapper accounts;
    private final TagMapper tags;
    private final PasswordService passwords;
    private final BootstrapProperties settings;

    public BootstrapServiceImpl(SiteConfigMapper siteConfigs, UserAccountMapper accounts,
            TagMapper tags, PasswordService passwords, BootstrapProperties settings) {
        this.siteConfigs = siteConfigs;
        this.accounts = accounts;
        this.tags = tags;
        this.passwords = passwords;
        this.settings = settings;
    }

    @Override
    @Transactional
    public void initialize() {
        // 仅补齐缺失的初始资料，重启服务不得覆盖站长已编辑内容或重置已有密码。
        boolean needsOwner = accounts.selectCount(Wrappers.<UserAccountEntity>lambdaQuery()
            .eq(UserAccountEntity::getUsername, OWNER_USERNAME)) == 0;
        if (needsOwner && (settings.initialPassword() == null
                || settings.initialPassword().length() < MIN_OWNER_PASSWORD_LENGTH)) {
            throw new IllegalStateException(MessageConstants.OWNER_PASSWORD_TOO_SHORT);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (siteConfigs.selectCount(null) == 0) {
            SiteConfigEntity profile = new SiteConfigEntity();
            profile.setDisplayName("阿霾");
            profile.setHeadline("一名 Java 后端工程师，转型从事 AI 智能体开发。");
            profile.setIntro("我复盘了自己的首个大语言模型项目，找出 37 个缺陷——因此我十分关注工具权限、token 预算，以及如何证明模型性能确实得到改善。");
            profile.setGithubUrl("https://github.com/WKING66");
            profile.setEmail("2899964923@qq.com");
            profile.setUpdatedAt(now);
            siteConfigs.insert(profile);
        }
        if (needsOwner) {
            UserAccountEntity owner = new UserAccountEntity();
            owner.setUsername(OWNER_USERNAME);
            owner.setType(AccountType.OWNER.code());
            owner.setPasswordHash(passwords.encode(settings.initialPassword()));
            owner.setStatus(AccountStatus.ENABLED.code());
            owner.setCreatedAt(now);
            owner.setUpdatedAt(now);
            accounts.insert(owner);
        }
        // 启动时一次性读取已有标签，避免为每项技术重复查询数据库。
        Set<String> existingSlugs = tags.selectList(Wrappers.<TagEntity>lambdaQuery()
                .eq(TagEntity::getKind, TagKind.TECH.code()))
            .stream().map(TagEntity::getSlug).collect(Collectors.toSet());
        for (Tech tech : TECH_STACK) {
            String slug = "tech-" + tech.slug();
            if (existingSlugs.contains(slug)) {
                continue;
            }
            TagEntity tag = new TagEntity();
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

    private record Tech(String slug, String name, TechGroup group, int order) {}

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
