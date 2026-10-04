package dev.amai.portfolio.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.entity.request.LoginRequest;
import dev.amai.portfolio.system.entity.vo.SessionApiVo;
import dev.amai.portfolio.system.entity.vo.SessionVo;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
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
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(AuthConstants.AUTH_SESSION_PATH)
@Tag(name = "用户会话", description = "用户登录、会话查询和登出")
public class AuthController {
    private final AuthService sessions;

    public AuthController(AuthService sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "查询用户会话", description = "返回登录状态；已登录时附带写操作所需的 CSRF 令牌")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "未登录返回 loggedIn=false；用户已登录返回会话信息",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = SessionApiVo.class))),
        @ApiResponse(responseCode = "403",
            description = "会话账号不存在或已停用",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    @ApiLog
    public R<SessionVo> current() {
        return R.success(sessions.current());
    }

    @PostMapping
    @ApiLog
    @Operation(summary = "用户密文登录", description = "浏览器以配置中的固定 RSA-OAEP SHA-256 公钥加密密码；"
        + "此接口不接受明文密码。校验来源与凭据后设置 HttpOnly 会话 Cookie；应用层加密不替代 HTTPS")
    @ApiResponses({
        @ApiResponse(responseCode = "200",
            description = "登录成功，返回会话与 CSRF 令牌",
            content = @Content(mediaType = "application/json",
                schema = @Schema(implementation = SessionApiVo.class))),
        @ApiResponse(responseCode = "400",
            description = "请求体无效、必填字段缺失或 RSA 密文格式无效",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "401",
            description = "用户名或密码错误",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "403",
            description = "来源不允许、传输未加密或账号已停用",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<SessionVo> login(@RequestHeader(value = "Origin", required = false) String origin,
            HttpServletRequest request, @Valid @RequestBody LoginRequest input) {
        return R.success(sessions.login(origin, clientKey(request), input));
    }

    @DeleteMapping
    @SaCheckLogin
    @ApiLog
    @Operation(summary = "用户登出", description = "需要有效用户会话与写入令牌",
        security = @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME),
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
            description = "CSRF 令牌无效",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<Void> logout() {
        sessions.logout();
        return R.success(null);
    }

    /** 登录参数错误返回 400，其他模块继续使用原有 422 校验契约。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> invalidLogin(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(R.failure(
            ApiErrorCode.AUTH_INVALID_CREDENTIAL_PAYLOAD.name(),
            SystemMessageConstants.LOGIN_CIPHERTEXT_INVALID,
            error.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage()).sorted().toList()));
    }

    private String clientKey(HttpServletRequest request) {
        // 不信任可伪造的 X-Forwarded-For；生产反向代理还应在入口层配置对应限流。
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }
}
