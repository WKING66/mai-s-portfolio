package dev.amai.portfolio.system.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 项目管理技术标签选择项。 */
@Schema(description = "技术标签选择项")
public record TechTagOptionVo(Long id, String name, String slug, String group, String logoKey) { }
