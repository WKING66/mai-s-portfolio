package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 项目管理完整快照。 */
@Schema(description = "项目管理完整快照")
public record AdminProjectVo(
    Long id, String slug, String title, String summary, String contribution, String outcome,
    String timeLabel, List<ProjectTagVo> tags, List<ProjectLinkVo> links, String status,
    boolean featured, int sortOrder, Long version, java.time.LocalDateTime publishedAt,
    java.time.LocalDateTime updatedAt
) implements ProjectItemVo { }
