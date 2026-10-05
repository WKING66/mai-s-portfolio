package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 两种视图使用不同投影，避免通过置空字段掩盖管理数据泄漏。 */
@Schema(oneOf = {PublicProjectVo.class, AdminProjectVo.class})
public sealed interface ProjectItemVo permits PublicProjectVo, AdminProjectVo { }
