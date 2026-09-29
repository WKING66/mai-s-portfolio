package dev.amai.portfolio.portfolio.entity.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "站长可查看和编辑的公开资料快照")
public record AdminProfileVo(
    @Schema(description = "公开昵称", example = "阿霾") String displayName,
    @Schema(description = "职业定位") String headline,
    @Schema(description = "个人介绍") String intro,
    @Schema(description = "GitHub 个人主页；未配置时为 null") String githubUrl,
    @Schema(description = "公开联系邮箱；未配置时为 null") String email,
    @Schema(description = "已就绪头像的站内地址；未上传时为 null") String avatarUrl,
    @Schema(description = "已就绪简历的站内地址；未上传时为 null") String resumeUrl,
    @Schema(description = "资料更新时间（UTC），更新请求需原样带回") LocalDateTime updatedAt
) {
}
