package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;

/** 项目管理完整快照。 */
@Schema(description = "项目管理完整快照")
public record ProjectEditorVo(
        Long id,

        String slug,

        String title,

        String summary,

        String contribution,

        String outcome,

        String timeLabel,

        List<ProjectTagVo> tags,

        List<ProjectLinkVo> links,

        String status,

        boolean featured,

        int sortOrder,

        Long version,

        LocalDateTime publishedAt,

        LocalDateTime updatedAt,

        @Schema(description = "当前封面与 OSS 签名地址；无图为 null")
        ProjectCoverVo cover
) implements ProjectItemVo { }
