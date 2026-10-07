package dev.amai.portfolio.portfolio.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.asset.api.AssetContentService;
import dev.amai.portfolio.asset.api.AssetQueryService;
import dev.amai.portfolio.asset.api.AssetType;
import dev.amai.portfolio.asset.api.ImageUploadPurpose;
import dev.amai.portfolio.portfolio.constant.ProjectConstants;
import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import dev.amai.portfolio.portfolio.entity.domain.ProjectMediaDO;
import dev.amai.portfolio.portfolio.enums.ProjectStatus;
import dev.amai.portfolio.portfolio.mapper.ProjectMapper;
import dev.amai.portfolio.portfolio.mapper.ProjectMediaMapper;
import dev.amai.portfolio.portfolio.service.ProjectCoverService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 不缓存公开授权；下架后的下一次请求立即拒绝，草稿不存在公开预览路径。 */
@Service
public class ProjectCoverServiceImpl implements ProjectCoverService {
    private final ProjectMapper projects;
    private final ProjectMediaMapper media;
    private final AssetQueryService query;
    private final AssetContentService content;

    public ProjectCoverServiceImpl(ProjectMapper projects, ProjectMediaMapper media,
            AssetQueryService query, AssetContentService content) {
        this.projects = projects;
        this.media = media;
        this.query = query;
        this.content = content;
    }

    @Override
    public AssetContentData openPublicCover(Long projectId) {
        if (projectId == null || projectId < 1) throw missing();
        var project = projects.selectById(projectId);
        if (project == null || !Objects.equals(project.getStatus(), ProjectStatus.PUBLISHED.code())) throw missing();
        var cover = media.selectOne(Wrappers.<ProjectMediaDO>lambdaQuery()
            .eq(ProjectMediaDO::getProjectId, projectId).eq(ProjectMediaDO::getRole, ProjectConstants.COVER_ROLE));
        if (cover == null || !query.isReadyImageForPurpose(cover.getMediaId(), ImageUploadPurpose.PROJECT_COVER)) throw missing();
        return content.openReady(cover.getMediaId(), AssetType.IMAGE).orElseThrow(this::missing);
    }

    private ApiException missing() { return new ApiException(ApiErrorCode.NOT_FOUND, ProjectMessageConstants.NOT_FOUND); }
}
