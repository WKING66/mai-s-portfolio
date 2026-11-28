package dev.amai.portfolio.portfolio.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.portfolio.api.ProfileKnowledgeSource;
import dev.amai.portfolio.portfolio.entity.domain.SiteConfigDO;
import dev.amai.portfolio.portfolio.mapper.SiteConfigMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProfileKnowledgeSourceImpl implements ProfileKnowledgeSource {
    private final SiteConfigMapper siteConfigMapper;

    public ProfileKnowledgeSourceImpl(SiteConfigMapper siteConfigMapper) {
        this.siteConfigMapper = siteConfigMapper;
    }

    @Override
    public Profile profile(long id) {
        var row = siteConfigMapper.selectOne(publicFields().eq(SiteConfigDO::getId, id));
        return row == null ? null : projection(row);
    }

    @Override
    public List<Profile> profiles() {
        return siteConfigMapper.selectList(publicFields()).stream()
                .map(ProfileKnowledgeSourceImpl::projection)
                .toList();
    }

    @Override
    public boolean lock(long id) {
        return siteConfigMapper.selectOne(Wrappers.<SiteConfigDO>lambdaQuery().select(SiteConfigDO::getId)
                .eq(SiteConfigDO::getId, id).last("FOR UPDATE")) != null;
    }

    private LambdaQueryWrapper<SiteConfigDO> publicFields() {
        return Wrappers.<SiteConfigDO>lambdaQuery().select(SiteConfigDO::getId, SiteConfigDO::getDisplayName,
                SiteConfigDO::getEmail, SiteConfigDO::getGithubUrl, SiteConfigDO::getHeadline, SiteConfigDO::getIntro);
    }

    static Profile projection(SiteConfigDO row) {
        return new Profile(row.getId(), row.getDisplayName(), row.getEmail(),
                row.getGithubUrl(), row.getHeadline(), row.getIntro());
    }
}
