package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "当前项目封面引用；URL 由业务域按公开/管理范围生成")
public record ProjectCoverVo(
    @Schema(description = "保存项目时使用的资产 ID，不代表访问授权") Long assetId,
    @Schema(description = "同源受控读取地址，不含 OSS 对象键或签名") String url,
    @Schema(description = "项目封面的替代文字") String alt
) { }
