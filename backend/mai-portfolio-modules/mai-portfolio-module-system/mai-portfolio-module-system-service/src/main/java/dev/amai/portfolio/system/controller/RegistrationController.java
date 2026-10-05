package dev.amai.portfolio.system.controller;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.constant.SystemMessageConstants;
import dev.amai.portfolio.system.entity.request.RegisterRequest;
import dev.amai.portfolio.system.entity.vo.RegistrationApiVo;
import dev.amai.portfolio.system.entity.vo.RegistrationVo;
import dev.amai.portfolio.system.service.AuthService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 匿名注册入口；复用 AuthService，注册和站长授权之间没有绑定关系。 */
@RestController
@RequestMapping(AuthConstants.AUTH_REGISTER_PATH)
@Tag(name = "用户注册", description = "创建启用的普通访客账号；公开阅读不要求注册")
public class RegistrationController {
    private final AuthService auth;

    public RegistrationController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping
    @ApiLog
    @Operation(summary = "普通用户密文注册", description = "使用与登录一致的固定 RSA-OAEP SHA-256 公钥；"
        + "服务端以 Argon2id 保存密码。固定普通账号和启用状态，不接收角色配置、不自动登录。"
        + "需允许的 Origin，无需现有会话或 CSRF；注册 IP 限流与登录分别计数。RSA 不能代替 HTTPS")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "创建成功；请随后登录",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = RegistrationApiVo.class))),
        @ApiResponse(responseCode = "400", description = "必填字段缺失、请求体或密文格式无效",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "403", description = "来源不允许或生产环境非 HTTPS",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "409", description = "用户名已使用（不区分大小写）",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "422", description = "用户名或解密后的密码不符合注册规则",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "429", description = "请求过于频繁",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = R.class)))
    })
    public R<RegistrationVo> register(
            @Parameter(name = "Origin", in = ParameterIn.HEADER, required = true,
                description = "浏览器自动发送的页面来源；缺失或不在允许列表内返回 403")
            @RequestHeader(value = "Origin", required = false) String origin,
            HttpServletRequest request, @Valid @RequestBody RegisterRequest input) {
        // 和登录一样只使用容器的直接来源地址，不信任客户端伪造的转发头。
        String address = request.getRemoteAddr();
        return R.success(auth.register(origin,
            address == null || address.isBlank() ? "unknown" : address, input));
    }

    /** 保持认证凭据错误的 400 契约；业务规则由 Service 返回 422。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> invalidRegistration(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(R.failure(
            ApiErrorCode.AUTH_INVALID_CREDENTIAL_PAYLOAD.name(), SystemMessageConstants.LOGIN_CIPHERTEXT_INVALID,
            error.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage()).sorted().toList()));
    }
}
