package dev.amai.portfolio.portfolio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** 外链编辑项；type 为 CODE/DEMO/DOCUMENTATION/OTHER。 */
@Schema(description = "外链编辑项；type 为 CODE/DEMO/DOCUMENTATION/OTHER")
public record ProjectLinkRequest(
    String type, String label, String url, Boolean visible, Integer sortOrder
) { }
