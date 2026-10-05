package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 分页项目；items 随明确的视图投影。 */
@Schema(description = "分页项目；items 随明确的视图投影")
public record ProjectPageVo(
    String view, int page, int size, long total, List<ProjectItemVo> items
) { }
