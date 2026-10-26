package dev.amai.portfolio.portfolio.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.portfolio.entity.request.UpdateProfileRequest;
import dev.amai.portfolio.portfolio.entity.vo.SiteProfileVo;
import dev.amai.portfolio.portfolio.service.SiteProfileService;
import dev.amai.portfolio.system.api.AuthConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/profile", produces = "application/json")
@Tag(name = "网站资料", description = "网站公开介绍、联系方式和技术栈")
public class ProfileController {
    private final SiteProfileService siteProfileService;

    public ProfileController(SiteProfileService siteProfileService) {
        this.siteProfileService = siteProfileService;
    }

    @GetMapping
    @Operation(summary = "获取网站资料", description = "返回公开字段、并发令牌及有期限的 OSS 地址")
    public ResponseEntity<R<SiteProfileVo>> profile() {
        return response(siteProfileService.profile());
    }

    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功", useReturnTypeSchema = true),
        @ApiResponse(responseCode = "401", description = "未登录"),
        @ApiResponse(responseCode = "403", description = "无 OWNER 权限或 CSRF 无效"),
        @ApiResponse(responseCode = "409", description = "并发资料更新冲突"),
        @ApiResponse(responseCode = "422", description = "资料字段不合法")
    })
    @PatchMapping
    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @ApiLog
    @Operation(summary = "更新网站资料", description = "仅 OWNER 可写；updatedAt 用于防止覆盖他人修改",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public ResponseEntity<R<SiteProfileVo>> update(@Valid @RequestBody UpdateProfileRequest request) {
        return response(siteProfileService.updateProfile(request));
    }

    private ResponseEntity<R<SiteProfileVo>> response(SiteProfileVo profile) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.success(profile));
    }
}
