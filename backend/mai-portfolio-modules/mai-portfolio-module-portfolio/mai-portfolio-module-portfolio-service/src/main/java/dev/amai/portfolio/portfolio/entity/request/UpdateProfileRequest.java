package dev.amai.portfolio.portfolio.entity.request;

import dev.amai.portfolio.portfolio.constant.PortfolioMessageConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

@Schema(description = "站长公开资料的完整可编辑字段；GitHub、邮箱传 null 或空白可取消公开")
public record UpdateProfileRequest(
    @NotBlank(message = PortfolioMessageConstants.PROFILE_NAME_REQUIRED)
    @Size(max = 100, message = PortfolioMessageConstants.PROFILE_NAME_TOO_LONG)
    @Schema(description = "公开昵称", example = "阿霾", requiredMode = Schema.RequiredMode.REQUIRED)
    String displayName,
    @NotBlank(message = PortfolioMessageConstants.PROFILE_HEADLINE_REQUIRED)
    @Size(max = 200, message = PortfolioMessageConstants.PROFILE_HEADLINE_TOO_LONG)
    @Schema(description = "职业定位", example = "Java 后端工程师，转型从事 AI 智能体开发", requiredMode = Schema.RequiredMode.REQUIRED)
    String headline,
    @NotBlank(message = PortfolioMessageConstants.PROFILE_INTRO_REQUIRED)
    @Size(max = 16000, message = PortfolioMessageConstants.PROFILE_INTRO_TOO_LONG)
    @Schema(description = "个人介绍；仅作为文本保存", requiredMode = Schema.RequiredMode.REQUIRED)
    String intro,
    @Size(max = 2048, message = PortfolioMessageConstants.PROFILE_GITHUB_TOO_LONG)
    @Schema(description = "GitHub 个人主页；null 或空白表示不公开", example = "https://github.com/WKING66", nullable = true)
    String githubUrl,
    @Email(message = PortfolioMessageConstants.PROFILE_EMAIL_INVALID)
    @Size(max = 254, message = PortfolioMessageConstants.PROFILE_EMAIL_TOO_LONG)
    @Schema(description = "公开联系邮箱；null 或空白表示不公开", example = "2899964923@qq.com", nullable = true)
    String email,
    @NotNull(message = PortfolioMessageConstants.PROFILE_UPDATED_AT_REQUIRED)
    @Schema(description = "上次读取到的资料更新时间（UTC）；若已被其他编辑更新则返回 409", example = "2026-09-26T10:00:00.123", requiredMode = Schema.RequiredMode.REQUIRED)
    LocalDateTime updatedAt
) {
    public UpdateProfileRequest {
        // 可选联系渠道允许用空白撤销公开；先规范化再运行 @Email 校验。
        if (email != null && email.isBlank()) {
            email = null;
        }
    }
}
