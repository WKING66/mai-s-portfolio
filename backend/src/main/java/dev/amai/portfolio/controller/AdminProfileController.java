package dev.amai.portfolio.controller;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.common.AuthConstants;
import dev.amai.portfolio.dto.AdminProfileApiEnvelope;
import dev.amai.portfolio.dto.AdminProfileResponse;
import dev.amai.portfolio.dto.UpdateProfileRequest;
import dev.amai.portfolio.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/profile")
@Tag(name = "站长资料", description = "读取和修改首页公开介绍及已批准的联系入口")
public class AdminProfileController {
    private final ProfileService profiles;

    public AdminProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "读取站长资料", description = "获取可编辑文本和更新时间；头像、简历仅显示已就绪的站内地址",
        security = @SecurityRequirement(name = AuthConstants.OPENAPI_OWNER_SESSION_SCHEME))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "资料快照",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AdminProfileApiEnvelope.class))),
        @ApiResponse(responseCode = "401", description = "未登录",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "403", description = "不是有效站长账号",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<AdminProfileResponse> getProfile() {
        return R.success(profiles.adminProfile());
    }

    @PatchMapping
    @Operation(summary = "更新站长公开资料", description = "一次提交全部可编辑文本字段；GitHub 和邮箱可传 null 取消公开。"
        + "须原样带回读取时的 updatedAt，资料已变更则返回 409；不会修改头像、简历或 SEO 配置",
        security = @SecurityRequirement(name = AuthConstants.OPENAPI_OWNER_SESSION_SCHEME),
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER,
            required = true, description = "从已登录会话的 csrfToken 获取"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新后的资料快照",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = AdminProfileApiEnvelope.class))),
        @ApiResponse(responseCode = "400", description = "请求体不是有效 JSON",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "401", description = "未登录",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "403", description = "无站长权限或 CSRF 令牌无效",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "409", description = "资料已由其他请求更新",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "422", description = "昵称、定位、介绍、邮箱或 GitHub 地址无效",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<AdminProfileResponse> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return R.success(profiles.updateProfile(request));
    }
}
