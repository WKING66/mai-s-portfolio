package dev.amai.portfolio.system.entity.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "绑定统一上传入口创建的本人头像资产，不接受账号 ID 或外部 URL")
public record AccountAvatarRequest(@NotNull @Positive Long assetId) {}
