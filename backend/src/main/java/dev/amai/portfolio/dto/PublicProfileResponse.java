package dev.amai.portfolio.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "公开个人资料")
public record PublicProfileResponse(
    @Schema(description = "公开昵称", example = "阿霾") String displayName,
    @Schema(description = "职业定位", example = "一名 Java 后端工程师，转型从事 AI 智能体开发。") String headline,
    @Schema(description = "个人介绍") String intro,
    @Schema(description = "GitHub 主页；未配置时为 null", example = "https://github.com/WKING66") String githubUrl,
    @Schema(description = "联系邮箱；未配置时为 null", example = "2899964923@qq.com") String email,
    @Schema(description = "已就绪头像的站内地址；未上传时为 null") String avatarUrl,
    @Schema(description = "已就绪简历的站内地址；未上传时为 null") String resumeUrl,
    @Schema(description = "公开技术标签") List<TechTagResponse> techStack
) {
}
