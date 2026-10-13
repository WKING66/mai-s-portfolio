package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 技术标签展示项。 */
@Schema(description = "技术标签展示项")
public record ProjectTagVo(
    Long id, String name, String slug, String group, String logoKey
) { }
