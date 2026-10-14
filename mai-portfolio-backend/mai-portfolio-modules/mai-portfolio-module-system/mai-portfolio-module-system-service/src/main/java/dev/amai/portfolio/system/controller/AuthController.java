package dev.amai.portfolio.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
import dev.amai.portfolio.system.entity.vo.RegistrationVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import dev.amai.portfolio.system.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** 字段约束由认证服务入口执行，统一抛出认证参数异常。 */
@RestController
@RequestMapping(value = AuthConstants.AUTH_PATH, produces = "application/json")
@Tag(name = "用户会话", description = "用户登录、会话查询和登出")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping(AuthConstants.AUTH_SESSION_ROUTE)
    @Operation(summary = "查询用户会话", description = "返回登录状态；已登录时附带写操作所需的 CSRF 令牌")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "未登录或账号已删除返回 loggedIn=false；用户已登录返回会话信息",
                useReturnTypeSchema = true),
        @ApiResponse(
                responseCode = "403",
                description = "会话账号已停用",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class)))
    })
    @ApiLog
    public R<SessionVo> current() {
        return R.success(authService.current());
    }

    @PostMapping(AuthConstants.AUTH_SESSION_ROUTE)
    @ApiLog
    @Operation(
            summary = "用户密文登录",
            description =
                    "浏览器以配置中的固定 RSA-OAEP SHA-256 公钥加密密码；"
                            + "此接口不接受明文密码。校验来源与凭据后设置 HttpOnly 会话 Cookie；应用层加密不替代 HTTPS")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "登录成功，返回会话与 CSRF 令牌",
                useReturnTypeSchema = true),
        @ApiResponse(
                responseCode = "400",
                description = "请求体无效、必填字段缺失或 RSA 密文格式无效",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class))),
        @ApiResponse(
                responseCode = "401",
                description = "用户名或密码错误",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class))),
        @ApiResponse(
                responseCode = "403",
                description = "来源不允许、传输未加密或账号已停用",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class)))
    })
    public R<SessionVo> login(
            @RequestHeader(value = "Origin", required = false) String origin,
            HttpServletRequest request,
            @RequestBody LoginRequest input) {
        return R.success(authService.login(origin, clientKey(request), input));
    }

    @PostMapping(AuthConstants.AUTH_REGISTER_ROUTE)
    @ApiLog
    @Operation(
            summary = "普通用户密文注册",
            description =
                    "使用与登录一致的固定 RSA-OAEP SHA-256 公钥；"
                            + "服务端以 Argon2id 保存密码。固定普通账号和启用状态，不接收角色配置、不自动登录。"
                            + "需允许的 Origin，无需现有会话或 CSRF；注册 IP 限流与登录分别计数。RSA 不能代替 HTTPS")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "创建成功；请随后登录", useReturnTypeSchema = true),
        @ApiResponse(
                responseCode = "400",
                description = "必填字段缺失、请求体或密文格式无效",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class))),
        @ApiResponse(
                responseCode = "403",
                description = "来源不允许或生产环境非 HTTPS",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class))),
        @ApiResponse(
                responseCode = "409",
                description = "用户名已使用（不区分大小写）",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class))),
        @ApiResponse(
                responseCode = "422",
                description = "用户名或解密后的密码不符合注册规则",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class))),
        @ApiResponse(
                responseCode = "429",
                description = "请求过于频繁",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class)))
    })
    public R<RegistrationVo> register(
            @Parameter(
                            name = "Origin",
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "浏览器自动发送的页面来源；缺失或不在允许列表内返回 403")
                    @RequestHeader(value = "Origin", required = false)
                    String origin,
            HttpServletRequest request,
            @RequestBody RegisterRequest input) {
        // 和登录一样只使用容器的直接来源地址，不信任客户端伪造的转发头。
        return R.success(authService.register(origin, clientKey(request), input));
    }

    @DeleteMapping(AuthConstants.AUTH_SESSION_ROUTE)
    @SaCheckLogin
    @ApiLog
    @Operation(
            summary = "用户登出",
            description = "需要有效用户会话与写入令牌",
            security = @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME),
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true,
                            description = "从已登录会话查询结果的 csrfToken 获取"))
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "登出成功",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class))),
        @ApiResponse(
                responseCode = "401",
                description = "未登录或会话失效",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class))),
        @ApiResponse(
                responseCode = "403",
                description = "CSRF 令牌无效",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = R.class)))
    })
    public R<Void> logout() {
        authService.logout();
        return R.success(null);
    }

    private String clientKey(HttpServletRequest request) {
        // 不信任可伪造的 X-Forwarded-For；生产反向代理还应在入口层配置对应限流。
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }
}
