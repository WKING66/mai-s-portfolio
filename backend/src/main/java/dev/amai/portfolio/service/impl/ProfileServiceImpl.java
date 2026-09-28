package dev.amai.portfolio.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.common.ApiErrorCode;
import dev.amai.portfolio.common.ApiException;
import dev.amai.portfolio.common.MessageConstants;
import dev.amai.portfolio.dto.AdminProfileResponse;
import dev.amai.portfolio.dto.PublicProfileResponse;
import dev.amai.portfolio.dto.TechTagResponse;
import dev.amai.portfolio.dto.UpdateProfileRequest;
import dev.amai.portfolio.entity.MediaAssetEntity;
import dev.amai.portfolio.entity.SiteConfigEntity;
import dev.amai.portfolio.entity.TagEntity;
import dev.amai.portfolio.enums.FeaturedStatus;
import dev.amai.portfolio.enums.MediaAssetType;
import dev.amai.portfolio.enums.MediaStatus;
import dev.amai.portfolio.enums.TagKind;
import dev.amai.portfolio.enums.TechGroup;
import dev.amai.portfolio.mapper.MediaAssetMapper;
import dev.amai.portfolio.mapper.SiteConfigMapper;
import dev.amai.portfolio.mapper.TagMapper;
import dev.amai.portfolio.service.ProfileService;
import dev.amai.portfolio.service.OwnerAccessService;
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
public class ProfileServiceImpl implements ProfileService {
    private static final Logger LOG = LoggerFactory.getLogger(ProfileServiceImpl.class);
    private static final Pattern GITHUB_PROFILE_PATH = Pattern.compile("/[A-Za-z0-9-]+/?");
    private final SiteConfigMapper siteConfigs;
    private final TagMapper tags;
    private final MediaAssetMapper media;
    private final OwnerAccessService owners;

    public ProfileServiceImpl(SiteConfigMapper siteConfigs, TagMapper tags, MediaAssetMapper media,
                              OwnerAccessService owners) {
        this.siteConfigs = siteConfigs;
        this.tags = tags;
        this.media = media;
        this.owners = owners;
    }

    @Override
    public PublicProfileResponse publicProfile() {
        SiteConfigEntity profile = siteProfile();
        List<TechTagResponse> techStack = tags.selectList(Wrappers.<TagEntity>lambdaQuery()
                .eq(TagEntity::getKind, TagKind.TECH.code())
                .eq(TagEntity::getIsFeatured, FeaturedStatus.FEATURED.code())
                .orderByAsc(TagEntity::getGroupCode, TagEntity::getSortOrder, TagEntity::getId))
            .stream().map(tag -> new TechTagResponse(tag.getName(), tag.getSlug(),
                TechGroup.fromCode(tag.getGroupCode()).name(), tag.getLogoKey())).toList();

        return new PublicProfileResponse(profile.getDisplayName(), profile.getHeadline(),
            profile.getIntro(), profile.getGithubUrl(), profile.getEmail(),
            mediaUrl(profile.getAvatarMediaId(), MediaAssetType.IMAGE),
            mediaUrl(profile.getResumeMediaId(), MediaAssetType.DOCUMENT), techStack);
    }

    @Override
    public AdminProfileResponse adminProfile() {
        owners.requireOwner();
        return toAdminResponse(siteProfile());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminProfileResponse updateProfile(UpdateProfileRequest request) {
        long ownerId = owners.requireOwner().id();
        String githubUrl = blankToNull(request.githubUrl());
        String email = blankToNull(request.email());
        validateGithubProfile(githubUrl);

        SiteConfigEntity existing = siteProfile();
        if (!existing.getUpdatedAt().equals(request.updatedAt())) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, MessageConstants.DATA_CONFLICT);
        }
        // DATETIME(3) 是并发令牌；同一毫秒内连续提交也必须产生不同的值。
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime updatedAt = now.isAfter(existing.getUpdatedAt())
            ? now : existing.getUpdatedAt().plus(1, ChronoUnit.MILLIS);
        int changed = siteConfigs.update(null, Wrappers.<SiteConfigEntity>lambdaUpdate()
            .eq(SiteConfigEntity::getId, existing.getId())
            .eq(SiteConfigEntity::getUpdatedAt, request.updatedAt())
            .set(SiteConfigEntity::getDisplayName, request.displayName().trim())
            .set(SiteConfigEntity::getHeadline, request.headline().trim())
            .set(SiteConfigEntity::getIntro, request.intro().trim())
            .set(SiteConfigEntity::getGithubUrl, githubUrl)
            .set(SiteConfigEntity::getEmail, email)
            .set(SiteConfigEntity::getUpdatedAt, updatedAt));
        if (changed != 1) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, MessageConstants.DATA_CONFLICT);
        }
        LOG.info("Public profile updated, operatorId={}, profileId={}", ownerId, existing.getId());
        return toAdminResponse(siteConfigs.selectById(existing.getId()));
    }

    private SiteConfigEntity siteProfile() {
        List<SiteConfigEntity> profiles = siteConfigs.selectList(null);
        if (profiles.size() != 1) {
            throw new IllegalStateException(MessageConstants.PROFILE_NOT_INITIALIZED);
        }
        return profiles.getFirst();
    }

    private AdminProfileResponse toAdminResponse(SiteConfigEntity profile) {
        return new AdminProfileResponse(profile.getDisplayName(), profile.getHeadline(),
            profile.getIntro(), profile.getGithubUrl(), profile.getEmail(),
            mediaUrl(profile.getAvatarMediaId(), MediaAssetType.IMAGE),
            mediaUrl(profile.getResumeMediaId(), MediaAssetType.DOCUMENT), profile.getUpdatedAt());
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
        throw new ApiException(ApiErrorCode.VALIDATION_FAILED, MessageConstants.PROFILE_GITHUB_INVALID);
    }

    private String mediaUrl(Long id, MediaAssetType type) {
        if (id == null) {
            return null;
        }
        // 公开资料只给已就绪且类型正确的站内地址；媒体下载接口仍须逐次独立鉴权。
        MediaAssetEntity asset = media.selectById(id);
        if (asset == null || asset.getStatus() != MediaStatus.READY.code()
                || asset.getAssetType() != type.code()) {
            return null;
        }
        return "/api/v1/media/" + id;
    }
}
