package dev.amai.portfolio.portfolio.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** 发布/下架请求，携带读取时的版本号。 */
@Schema(description = "发布/下架请求，携带读取时的版本号")
public record ProjectVersionRequest(
    Long version
) { }
