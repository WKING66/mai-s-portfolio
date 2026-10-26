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
    private final SiteConfigMapper siteConfigMapper;

    public PortfolioBootstrapServiceImpl(SiteConfigMapper siteConfigMapper) {
        this.siteConfigMapper = siteConfigMapper;
    }

    @Override
    @Transactional
    public void initialize() {
        // 1. 持有事务级 advisory lock，串行化多实例的首次初始化，提交/回滚时自动释放。
        siteConfigMapper.acquireBootstrapLock();
        if (siteConfigMapper.selectCount(null) != 0) {
            return;
        }
        // 2. 只补齐空库，绝不覆盖已有资料；写入失败由当前数据库事务回滚。
        SiteConfigDO profile = new SiteConfigDO();
        profile.setDisplayName("阿霾");
        profile.setHeadline("一名 Java 后端工程师，转型从事 AI 智能体开发。");
        profile.setIntro("我复盘了自己的首个大语言模型项目，找出 37 个缺陷——因此我十分关注工具权限、token 预算，以及如何证明模型性能确实得到改善。");
        profile.setGithubUrl("https://github.com/WKING66");
        profile.setEmail("2899964923@qq.com");
        profile.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        siteConfigMapper.insert(profile);
    }
}
