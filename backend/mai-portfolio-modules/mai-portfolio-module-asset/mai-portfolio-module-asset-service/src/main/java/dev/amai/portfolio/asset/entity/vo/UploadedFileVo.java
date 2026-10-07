package dev.amai.portfolio.asset.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 上传不是发布；预览仍要求站长认证，公开读取由具体业务引用决定。 */
@Schema(description = "上传后的内部资产编号与受保护的预览入口；不返回 OSS 对象键或签名")
public record UploadedFileVo(
    @Schema(description = "已 READY 的资产自增 ID；保存项目时绑定") Long id,
    @Schema(description = "要求 OWNER 的同源图片预览路径") String previewUrl
) { }
