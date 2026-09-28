package dev.amai.portfolio.controller;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.dto.PublicProfileResponse;
import dev.amai.portfolio.dto.PublicProfileApiEnvelope;
import dev.amai.portfolio.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/profile")
@Tag(name = "公开资料", description = "访客可读取的个人介绍和技术栈")
public class ProfileController {
    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping
    @Operation(summary = "获取公开个人资料", description = "只返回已确认公开的介绍、联系方式和技术栈；未上传的头像和简历无地址")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "已获准公开的个人资料及技术栈",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = PublicProfileApiEnvelope.class))),
        @ApiResponse(responseCode = "500",
            description = "站点公开资料未正确初始化",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<PublicProfileResponse> publicProfile() {
        return R.success(profiles.publicProfile());
    }
}
