package dev.amai.portfolio.asset.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.amai.portfolio.asset.api.ImageUploadPurpose;
import dev.amai.portfolio.asset.entity.domain.MediaAssetDO;
import dev.amai.portfolio.asset.mapper.MediaAssetMapper;
import org.junit.jupiter.api.Test;

/** 用途隔离不能仅凭 READY 状态把私人头像绑定成公开封面。 */
class AssetQueryServiceImplTest {
    private final MediaAssetMapper mapper = mock(MediaAssetMapper.class);
    private final AssetQueryServiceImpl service = new AssetQueryServiceImpl(mapper);

    @Test
    void rejectsPrivateAvatarsWrongNamespaceDocumentsAndUnreadyImages() {
        MediaAssetDO asset = new MediaAssetDO();
        asset.setStatus(1);
        asset.setAssetType(0);
        when(mapper.selectById(19L)).thenReturn(asset);
        for (String key : new String[] {"account/avatars/private.png", "project/covers-other/image.png", null}) {
            asset.setStorageKey(key);
            assertThat(service.isReadyImageForPurpose(19L, ImageUploadPurpose.PROJECT_COVER)).isFalse();
        }
        asset.setStorageKey("project/covers/uuid.png");
        assertThat(service.isReadyImageForPurpose(19L, ImageUploadPurpose.PROJECT_COVER)).isTrue();
        asset.setAssetType(1);
        assertThat(service.isReadyImageForPurpose(19L, ImageUploadPurpose.PROJECT_COVER)).isFalse();
        asset.setAssetType(0);
        asset.setStatus(2);
        assertThat(service.isReadyImageForPurpose(19L, ImageUploadPurpose.PROJECT_COVER)).isFalse();
        assertThat(service.isReadyImageForPurpose(0L, ImageUploadPurpose.PROJECT_COVER)).isFalse();
    }
}
