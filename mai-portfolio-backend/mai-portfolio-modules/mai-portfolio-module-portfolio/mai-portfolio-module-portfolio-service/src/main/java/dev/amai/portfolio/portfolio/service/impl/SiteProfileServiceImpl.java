package dev.amai.portfolio.portfolio.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.portfolio.constant.PortfolioMessageConstants;
import dev.amai.portfolio.portfolio.entity.domain.SiteConfigDO;
import dev.amai.portfolio.portfolio.entity.request.UpdateProfileRequest;
import dev.amai.portfolio.asset.api.AssetResourceData;
import dev.amai.portfolio.portfolio.entity.vo.SiteProfileVo;
import dev.amai.portfolio.portfolio.entity.vo.TechTagVo;
import dev.amai.portfolio.portfolio.mapper.SiteConfigMapper;
import dev.amai.portfolio.portfolio.service.SiteProfileService;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.web.WebMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SiteProfileServiceImpl implements SiteProfileService {
    private static final Logger LOG = LoggerFactory.getLogger(SiteProfileServiceImpl.class);
    private static final Pattern GITHUB_PROFILE_PATH = Pattern.compile("/[A-Za-z0-9-]+/?");
    private final SiteConfigMapper siteConfigs;
    private final TaxonomyQueryService taxonomy;
    private final AssetQueryService assets;

    public SiteProfileServiceImpl(SiteConfigMapper siteConfigs, TaxonomyQueryService taxonomy,
            AssetQueryService assets) {
        this.siteConfigs = siteConfigs;
        this.taxonomy = taxonomy;
        this.assets = assets;
    }

    @Override
    public SiteProfileVo profile() {
        return toVo(siteProfile());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SiteProfileVo updateProfile(UpdateProfileRequest request) {
        String githubUrl = blankToNull(request.githubUrl());
        String email = blankToNull(request.email());
        validateGithubProfile(githubUrl);

        SiteConfigDO existing = siteProfile();
        if (!existing.getUpdatedAt().equals(request.updatedAt())) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, WebMessageConstants.DATA_CONFLICT);
        }
        // TIMESTAMP(3) 是并发令牌；同一毫秒内连续提交也必须产生不同的值。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime updatedAt = now.isAfter(existing.getUpdatedAt())
            ? now : existing.getUpdatedAt().plus(1, ChronoUnit.MILLIS);
        int changed = siteConfigs.update(null, Wrappers.<SiteConfigDO>lambdaUpdate()
            .eq(SiteConfigDO::getId, existing.getId())
            .eq(SiteConfigDO::getUpdatedAt, request.updatedAt())
            .set(SiteConfigDO::getDisplayName, request.displayName().trim())
            .set(SiteConfigDO::getHeadline, request.headline().trim())
            .set(SiteConfigDO::getIntro, request.intro().trim())
            .set(SiteConfigDO::getGithubUrl, githubUrl)
            .set(SiteConfigDO::getEmail, email)
            .set(SiteConfigDO::getUpdatedAt, updatedAt));
        if (changed != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, WebMessageConstants.DATA_CONFLICT);
        }
        LOG.info("Site profile updated, profileId={}", existing.getId());
        return toVo(siteConfigs.selectById(existing.getId()));
    }

    private SiteConfigDO siteProfile() {
        List<SiteConfigDO> profiles = siteConfigs.selectList(null);
        if (profiles.size() != 1) {
            throw new IllegalStateException(PortfolioMessageConstants.PROFILE_NOT_INITIALIZED);
        }
        return profiles.getFirst();
    }

    private SiteProfileVo toVo(SiteConfigDO profile) {
        AssetResourceData avatar = assets.findReady(profile.getAvatarMediaId(), AssetType.IMAGE).orElse(null);
        AssetResourceData resume = assets.findReady(profile.getResumeMediaId(), AssetType.DOCUMENT).orElse(null);
        List<TechTagVo> techStack = taxonomy.featuredTechTags().stream()
            .map(tag -> new TechTagVo(tag.name(), tag.slug(), tag.group(), tag.logoKey()))
            .toList();
        return new SiteProfileVo(profile.getDisplayName(), profile.getHeadline(),
            profile.getIntro(), profile.getGithubUrl(), profile.getEmail(),
            avatar == null ? null : avatar.resourceUrl(), resume == null ? null : resume.resourceUrl(),
            techStack, profile.getUpdatedAt(), avatar == null ? null : avatar.expiresAt(),
            resume == null ? null : resume.expiresAt());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateGithubProfile(String value) {
        if (value == null) {
            return;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getPort() == -1
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && GITHUB_PROFILE_PATH.matcher(uri.getPath()).matches()) {
                return;
            }
        } catch (IllegalArgumentException invalidUrl) {
            // 非法 URI 转换为同一个安全的业务错误，不暴露解析细节。
        }
        throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
            PortfolioMessageConstants.PROFILE_GITHUB_INVALID);
    }
}
