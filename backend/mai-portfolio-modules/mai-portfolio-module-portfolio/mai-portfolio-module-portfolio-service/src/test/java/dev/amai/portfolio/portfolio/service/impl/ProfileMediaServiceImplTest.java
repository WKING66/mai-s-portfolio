package dev.amai.portfolio.portfolio.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetContentService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.portfolio.entity.domain.SiteConfigDO;
import dev.amai.portfolio.portfolio.mapper.SiteConfigMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProfileMediaServiceImplTest {
    @Test
    void opensOnlyReadyMediaReferencedByThePublicProfile() {
        SiteConfigMapper siteConfigs = mock(SiteConfigMapper.class);
        AssetContentService assets = mock(AssetContentService.class);
        SiteConfigDO profile = new SiteConfigDO();
        profile.setAvatarMediaId(10L);
        when(siteConfigs.selectList(null)).thenReturn(List.of(profile));
        AssetContentData expected = new AssetContentData(
            new ByteArrayInputStream(new byte[] {1}), 1, "image/png", "avatar.png", AssetType.IMAGE);
        when(assets.openReady(10L, AssetType.IMAGE)).thenReturn(Optional.of(expected));
        ProfileMediaServiceImpl service = new ProfileMediaServiceImpl(siteConfigs, assets);

        assertThat(service.openPublicMedia(10L)).isSameAs(expected);
    }

    @Test
    void rejectsReadyAssetsThatAreNotReferencedByThePublicProfile() {
        SiteConfigMapper siteConfigs = mock(SiteConfigMapper.class);
        AssetContentService assets = mock(AssetContentService.class);
        SiteConfigDO profile = new SiteConfigDO();
        profile.setAvatarMediaId(10L);
        profile.setResumeMediaId(20L);
        when(siteConfigs.selectList(null)).thenReturn(List.of(profile));
        ProfileMediaServiceImpl service = new ProfileMediaServiceImpl(siteConfigs, assets);

        assertThatThrownBy(() -> service.openPublicMedia(99L))
            .isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
        verifyNoInteractions(assets);
    }
}
