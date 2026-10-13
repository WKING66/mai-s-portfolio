package dev.amai.portfolio.portfolio.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.amai.portfolio.portfolio.entity.domain.SiteConfigDO;
import dev.amai.portfolio.portfolio.mapper.SiteConfigMapper;
import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import java.util.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class SiteProfileServiceImplTest {
    @Test void siteReadUsesOneTruthAndSignedResourcesWithExpiryWithoutProxyPaths() {
        var mapper = mock(SiteConfigMapper.class);
        var taxonomy = mock(TaxonomyQueryService.class);
        var assets = mock(AssetQueryService.class);
        var row = new SiteConfigDO();
        row.setId(1L); row.setDisplayName("site author"); row.setAvatarMediaId(19L); row.setResumeMediaId(20L);
        row.setUpdatedAt(LocalDateTime.of(2026, 10, 7, 0, 0));
        when(mapper.selectList(null)).thenReturn(List.of(row));
        when(taxonomy.featuredTechTags()).thenReturn(List.of());
        Instant expiry = Instant.parse("2030-01-01T00:00:00Z");
        when(assets.findReady(19L, AssetType.IMAGE)).thenReturn(Optional.of(new AssetResourceData(19L, AssetType.IMAGE,
            AssetUsage.IMAGE, null, "image.png", "image/png", 12L, "https://oss.test/avatar", expiry)));
        when(assets.findReady(20L, AssetType.DOCUMENT)).thenReturn(Optional.of(new AssetResourceData(20L, AssetType.DOCUMENT,
            AssetUsage.ATTACHMENT, null, "resume.pdf", "application/pdf", 12L, "https://oss.test/resume", expiry)));
        var result = new SiteProfileServiceImpl(mapper, taxonomy, assets).profile();
        assertThat(result.displayName()).isEqualTo("site author");
        assertThat(result.avatarUrl()).isEqualTo("https://oss.test/avatar");
        assertThat(result.resumeUrl()).isEqualTo("https://oss.test/resume");
        assertThat(result.avatarExpiresAt()).isEqualTo(expiry);
        assertThat(result.updatedAt()).isEqualTo(row.getUpdatedAt());
    }
}
