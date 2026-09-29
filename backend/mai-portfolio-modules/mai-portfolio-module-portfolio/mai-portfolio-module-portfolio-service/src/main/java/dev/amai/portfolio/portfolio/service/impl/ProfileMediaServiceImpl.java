package dev.amai.portfolio.portfolio.service.impl;

import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetContentService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.portfolio.constant.PortfolioMessageConstants;
import dev.amai.portfolio.portfolio.entity.domain.SiteConfigDO;
import dev.amai.portfolio.portfolio.mapper.SiteConfigMapper;
import dev.amai.portfolio.portfolio.service.ProfileMediaService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 根据站点配置的实际引用关系控制个人媒体公开范围。 */
@Service
public class ProfileMediaServiceImpl implements ProfileMediaService {
    private final SiteConfigMapper siteConfigs;
    private final AssetContentService assets;

    public ProfileMediaServiceImpl(SiteConfigMapper siteConfigs, AssetContentService assets) {
        this.siteConfigs = siteConfigs;
        this.assets = assets;
    }

    @Override
    public AssetContentData openPublicMedia(Long assetId) {
        SiteConfigDO profile = siteProfile();
        AssetType expectedType;
        if (Objects.equals(profile.getAvatarMediaId(), assetId)) {
            expectedType = AssetType.IMAGE;
        } else if (Objects.equals(profile.getResumeMediaId(), assetId)) {
            expectedType = AssetType.DOCUMENT;
        } else {
            throw mediaNotFound();
        }
        return assets.openReady(assetId, expectedType).orElseThrow(this::mediaNotFound);
    }

    private SiteConfigDO siteProfile() {
        List<SiteConfigDO> profiles = siteConfigs.selectList(null);
        if (profiles.size() != 1) {
            throw new IllegalStateException(PortfolioMessageConstants.PROFILE_NOT_INITIALIZED);
        }
        return profiles.getFirst();
    }

    private ApiException mediaNotFound() {
        return new ApiException(ApiErrorCode.NOT_FOUND, PortfolioMessageConstants.PROFILE_MEDIA_NOT_FOUND);
    }
}
