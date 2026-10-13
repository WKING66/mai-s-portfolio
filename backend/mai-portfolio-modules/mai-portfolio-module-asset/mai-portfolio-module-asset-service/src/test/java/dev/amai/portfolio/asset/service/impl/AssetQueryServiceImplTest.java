package dev.amai.portfolio.asset.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import dev.amai.portfolio.asset.api.*;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import dev.amai.portfolio.storage.*;
import java.time.Instant;
import java.util.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssetQueryServiceImplTest {
    private final MediaAssetMapper mapper = mock(MediaAssetMapper.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final AssetQueryServiceImpl service = new AssetQueryServiceImpl(mapper, storage);

    @BeforeAll static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), MediaAssetDO.class);
    }

    @Test void bulkQueryUsesReadyTypeAndIdsWithoutRetrievingObjectBytesOrInferringUsageFromKeys() {
        MediaAssetDO row = new MediaAssetDO();
        row.setId(19L); row.setAssetType(0); row.setStatus(1); row.setUsage("IMAGE");
        row.setUploaderAccountId(7L); row.setStorageKey("legacy/arbitrary/object.png");
        row.setOriginalFilename("image.png"); row.setMimeType("image/png"); row.setByteSize(12L);
        when(mapper.selectList(any())).thenReturn(List.of(row));
        Instant expiry = Instant.parse("2030-01-01T00:00:00Z");
        when(storage.accessUrl(row.getStorageKey())).thenReturn(new StoredObjectAccessUrl("https://oss.test/signed", expiry));
        var found = service.findReady(Set.of(19L, 20L), AssetType.IMAGE);
        assertThat(found.keySet()).containsExactly(19L);
        assertThat(found.get(19L).usage()).isEqualTo(AssetUsage.IMAGE);
        assertThat(found.get(19L).expiresAt()).isEqualTo(expiry);
        var query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains(" IN ", "status", "asset_type");
        assertThat(query.getValue().getParamNameValuePairs().values()).contains(19L, 20L, 1, 0);
        verify(storage, never()).open(any());
        verify(storage, never()).exists(any());
    }

    @Test void invalidIdsAndEmptyBatchHaveNoExternalEffects() {
        assertThat(service.findReady((Long) null, AssetType.IMAGE)).isEmpty();
        assertThat(service.findReady(0L, AssetType.IMAGE)).isEmpty();
        assertThat(service.findReady(Set.of(), AssetType.IMAGE)).isEmpty();
        verifyNoInteractions(mapper, storage);
    }
    @Test void metadataQueryDoesNotDependOnStorageAvailability() {
        var row = new MediaAssetDO();
        row.setId(19L); row.setStatus(1); row.setAssetType(0); row.setUsage("AVATAR"); row.setUploaderAccountId(7L);
        when(mapper.selectById(19L)).thenReturn(row);
        assertThat(service.findReadyMetadata(19L)).contains(new AssetMetadataData(19L, AssetType.IMAGE, AssetUsage.AVATAR, 7L));
        row.setStatus(2);
        assertThat(service.findReadyMetadata(19L)).isEmpty();
        verifyNoInteractions(storage);
    }
}
