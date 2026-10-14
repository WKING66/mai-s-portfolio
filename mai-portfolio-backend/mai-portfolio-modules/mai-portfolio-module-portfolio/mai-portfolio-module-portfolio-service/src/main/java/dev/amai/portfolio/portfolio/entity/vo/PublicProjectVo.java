package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 公开卡片；不包含管理状态、版本号和隐藏外链。 */
@Schema(description = "公开卡片；不包含管理状态、版本号和隐藏外链")
public record PublicProjectVo(
    Long id, String slug, String title, String summary, String contribution, String outcome,
    String timeLabel, List<ProjectTagVo> tags, List<PublicProjectLinkVo> links,
    @Schema(description = "已发布项目的当前封面；无图为 null，不暴露对象存储地址") ProjectCoverVo cover
) implements ProjectItemVo { }
