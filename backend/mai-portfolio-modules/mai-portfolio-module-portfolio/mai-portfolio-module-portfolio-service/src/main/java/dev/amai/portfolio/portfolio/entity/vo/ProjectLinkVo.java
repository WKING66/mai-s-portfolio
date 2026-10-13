package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 项目外部入口。 */
@Schema(description = "项目外部入口")
public record ProjectLinkVo(
    String type, String label, String url, Boolean visible, int sortOrder
) { }
