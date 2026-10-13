package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 公开外链不带后台可见性及排序编辑字段。 */
@Schema(description = "项目公开外部入口")
public record PublicProjectLinkVo(String type, String label, String url) { }
