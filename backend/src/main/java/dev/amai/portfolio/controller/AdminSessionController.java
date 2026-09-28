package dev.amai.portfolio.controller;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.common.AuthConstants;
import dev.amai.portfolio.dto.LoginRequest;
import dev.amai.portfolio.dto.LoginChallengeResponse;
import dev.amai.portfolio.dto.LoginChallengeApiEnvelope;
import dev.amai.portfolio.dto.SessionApiEnvelope;
import dev.amai.portfolio.dto.SessionResponse;
import dev.amai.portfolio.service.AdminSessionService;
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
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/admin/session")
@Tag(name = "站长会话", description = "站长登录、会话查询和登出")
public class AdminSessionController {
    private final AdminSessionService sessions;

    public AdminSessionController(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "查询站长会话", description = "返回登录状态；已登录时附带写操作所需的 CSRF 令牌")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "未登录返回 loggedIn=false；站长已登录返回会话信息",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = SessionApiEnvelope.class))),
        @ApiResponse(responseCode = "403",
            description = "原会话账号已停用或失去站长角色",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<SessionResponse> current() {
        return R.success(sessions.current());
    }

    @GetMapping("/challenge")
    @Operation(summary = "获取一次性登录公钥", description = "返回短时 RSA-OAEP SHA-256 公钥和 challengeId；"
        + "密钥只在当前后端实例内存中有效，登录提交后立即失效")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "一次性登录加密凭证，不可缓存",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = LoginChallengeApiEnvelope.class))),
        @ApiResponse(responseCode = "429", description = "未消费的登录凭证过多，请稍后再试",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<LoginChallengeResponse> challenge(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return R.success(sessions.issueChallenge());
    }

    @PostMapping
    @Operation(summary = "站长密文登录", description = "先获取一次性公钥，浏览器以 RSA-OAEP SHA-256 加密密码；"
        + "此接口不接受明文密码。校验来源与凭据后设置 HttpOnly 会话 Cookie；应用层加密不替代 HTTPS")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "登录成功，返回会话与 CSRF 令牌",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = SessionApiEnvelope.class))),
        @ApiResponse(responseCode = "400",
            description = "请求体不是有效 JSON",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "401",
            description = "用户名或密码错误",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "403",
            description = "来源不允许、传输未加密或账号没有站长权限",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "422",
            description = "字段校验失败、凭证过期/重放或密文无效",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<SessionResponse> login(@RequestHeader(value = "Origin", required = false) String origin,
                                    @Valid @RequestBody LoginRequest input) {
        return R.success(sessions.login(origin, input));
    }

    @DeleteMapping
    @Operation(summary = "站长登出", description = "需要有效站长会话与写入令牌",
        security = @SecurityRequirement(name = AuthConstants.OPENAPI_OWNER_SESSION_SCHEME),
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER,
            required = true, description = "从已登录会话查询结果的 csrfToken 获取"))
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "登出成功",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "401",
            description = "未登录或会话失效",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "403",
            description = "无站长权限或 CSRF 令牌无效",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<Void> logout() {
        sessions.logout();
        return R.success(null);
    }
}
