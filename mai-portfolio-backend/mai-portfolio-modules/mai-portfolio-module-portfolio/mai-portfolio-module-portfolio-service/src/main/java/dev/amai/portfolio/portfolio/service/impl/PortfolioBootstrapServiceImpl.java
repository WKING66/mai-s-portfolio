package dev.amai.portfolio.portfolio.service.impl;

import dev.amai.portfolio.portfolio.entity.domain.SiteConfigDO;
import dev.amai.portfolio.portfolio.mapper.SiteConfigMapper;
import dev.amai.portfolio.portfolio.service.PortfolioBootstrapService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortfolioBootstrapServiceImpl implements PortfolioBootstrapService {
    private final SiteConfigMapper siteConfigs;

    public PortfolioBootstrapServiceImpl(SiteConfigMapper siteConfigs) {
        this.siteConfigs = siteConfigs;
    }

    @Override
    @Transactional
    public void initialize() {
        siteConfigs.acquireBootstrapLock();
        if (siteConfigs.selectCount(null) != 0) {
            return;
        }
        SiteConfigDO profile = new SiteConfigDO();
        profile.setDisplayName("阿霾");
        profile.setHeadline("一名 Java 后端工程师，转型从事 AI 智能体开发。");
        profile.setIntro("我复盘了自己的首个大语言模型项目，找出 37 个缺陷——因此我十分关注工具权限、token 预算，以及如何证明模型性能确实得到改善。");
        profile.setGithubUrl("https://github.com/WKING66");
        profile.setEmail("2899964923@qq.com");
        profile.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        siteConfigs.insert(profile);
    }
}
