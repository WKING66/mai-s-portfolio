package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "公开技术标签")
public record TechTagVo(
    @Schema(description = "技术名称", example = "Java") String name,
    @Schema(description = "稳定标识", example = "tech-java") String slug,
    @Schema(description = "分类：LANGUAGE、FRAMEWORK、TOOL、INFRA、DATA", example = "LANGUAGE") String group,
    @Schema(description = "图标键；未配置时为 null") String logoKey
) {
}
