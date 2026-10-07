package dev.amai.portfolio.portfolio.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetContentService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.ImageUploadPurpose;
import dev.amai.portfolio.portfolio.entity.domain.ProjectDO;
import dev.amai.portfolio.portfolio.entity.domain.ProjectMediaDO;
import dev.amai.portfolio.portfolio.mapper.ProjectMapper;
import dev.amai.portfolio.portfolio.mapper.ProjectMediaMapper;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.io.ByteArrayInputStream;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 公开封面以当前发布状态及当前封面关系授权，而非允许任意资产 ID 读取。 */
class ProjectCoverServiceImplTest {
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectMediaMapper media = mock(ProjectMediaMapper.class);
    private final AssetQueryService query = mock(AssetQueryService.class);
    private final AssetContentService content = mock(AssetContentService.class);
    private final ProjectCoverServiceImpl service = new ProjectCoverServiceImpl(projects, media, query, content);

    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "unit-test"), ProjectMediaDO.class);
    }

    @Test
    void draftMissingAndInvalidProjectsDoNotOpenFiles() {
        ProjectDO draft = new ProjectDO();
        draft.setStatus(0);
        when(projects.selectById(1L)).thenReturn(draft);
        for (Long id : new Long[] {null, 0L, 1L, 99L}) assertMissing(id);
        verifyNoInteractions(media, query, content);
    }

    @Test
    void currentPublishedCoverIsReadableButUnpublishImmediatelyRevokesNextRead() {
        ProjectDO project = new ProjectDO();
        project.setStatus(1);
        when(projects.selectById(1L)).thenReturn(project);
        ProjectMediaDO cover = new ProjectMediaDO();
        cover.setMediaId(19L);
        when(media.selectOne(any())).thenReturn(cover);
        when(query.isReadyImageForPurpose(19L, ImageUploadPurpose.PROJECT_COVER)).thenReturn(true);
        var file = new AssetContentData(new ByteArrayInputStream(new byte[] {1}), 1, "image/png", "cover.png", AssetType.IMAGE);
        when(content.openReady(19L, AssetType.IMAGE)).thenReturn(Optional.of(file));
        assertThat(service.openPublicCover(1L)).isSameAs(file);
        project.setStatus(0);
        assertMissing(1L);
    }

    @Test
    void privateOrFailedAssetsCannotBeExposedEvenByAProjectRelation() {
        ProjectDO project = new ProjectDO();
        project.setStatus(1);
        when(projects.selectById(1L)).thenReturn(project);
        ProjectMediaDO cover = new ProjectMediaDO();
        cover.setMediaId(19L);
        when(media.selectOne(any())).thenReturn(cover);
        assertMissing(1L);
        verifyNoInteractions(content);
    }

    private void assertMissing(Long id) {
        assertThatThrownBy(() -> service.openPublicCover(id)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.code()).isEqualTo(ApiErrorCode.NOT_FOUND));
    }
}
