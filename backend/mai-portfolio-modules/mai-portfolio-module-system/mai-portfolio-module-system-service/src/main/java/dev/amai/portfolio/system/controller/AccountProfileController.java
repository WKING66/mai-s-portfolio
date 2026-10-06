package dev.amai.portfolio.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import dev.amai.portfolio.asset.api.AssetContentData;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.constant.AccountMessageConstants;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.system.entity.request.AccountPasswordRequest;
import dev.amai.portfolio.system.entity.request.AccountProfileRequest;
import dev.amai.portfolio.system.entity.vo.AccountProfileApiVo;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.service.AccountProfileService;
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
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import java.util.List;

/** 普通用户/站长共用的本人资料入口，独立于站长修改公开首页资料的接口。 */
@RestController
@RequestMapping(AuthConstants.ACCOUNT_PATH)
@SaCheckLogin
@Tag(name = "个人中心", description = "本人昵称、头像与密码维护；所有写操作需要现有 Cookie 会话和 CSRF")
@SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "未登录或会话失效",
        content = @Content(schema = @Schema(implementation = R.class))),
    @ApiResponse(responseCode = "403", description = "账号停用、HTTPS 要求或写操作 CSRF 无效",
        content = @Content(schema = @Schema(implementation = R.class)))
})
public class AccountProfileController {
    private final AccountProfileService accountProfile;

    public AccountProfileController(AccountProfileService accountProfile) {
        this.accountProfile = accountProfile;
    }

    @GetMapping("/profile")
    @ApiLog
    @Operation(summary = "查询本人资料", description = "昵称未设置时使用登录用户名，不创建空资料记录")
    @ApiResponse(responseCode = "200", description = "本人资料",
        content = @Content(schema = @Schema(implementation = AccountProfileApiVo.class)))
    public ResponseEntity<R<AccountProfileVo>> current() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
            .body(R.success(accountProfile.current()));
    }

    @PutMapping("/profile")
    @ApiLog
    @Operation(summary = "修改本人昵称", description = "不接受账号 ID 或角色；空昵称回退用户名",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER,
            required = true, description = "会话响应中的 csrfToken"))
    @ApiResponse(responseCode = "200", description = "修改后的本人资料",
        content = @Content(schema = @Schema(implementation = AccountProfileApiVo.class)))
    @ApiResponse(responseCode = "422", description = "昵称超长或含控制字符",
        content = @Content(schema = @Schema(implementation = R.class)))
    public R<AccountProfileVo> update(@Valid @RequestBody AccountProfileRequest request) {
        return R.success(accountProfile.update(request));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiLog
    @Operation(summary = "上传本人头像", description = "仅 PNG/JPEG，默认 2 MiB、宽高不超过 2048；"
        + "内容解码并重新编码后保存到私有对象存储。不会删除或覆盖旧头像",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER,
            required = true, description = "会话响应中的 csrfToken"))
    @ApiResponse(responseCode = "200", description = "新头像已经绑定到本人资料",
        content = @Content(schema = @Schema(implementation = AccountProfileApiVo.class)))
    @ApiResponse(responseCode = "422", description = "大小、图片类型或像素尺寸不符合规则",
        content = @Content(schema = @Schema(implementation = R.class)))
    @ApiResponse(responseCode = "500", description = "对象存储或资产状态写入失败；不返回假成功",
        content = @Content(schema = @Schema(implementation = R.class)))
    public R<AccountProfileVo> upload(@RequestPart("file") MultipartFile file) {
        return R.success(accountProfile.uploadAvatar(file));
    }

    @GetMapping("/avatar")
    @ApiLog
    @Operation(summary = "读取本人头像", description = "只读取当前 Cookie 对应账号的头像；"
        + "URL 中的刷新标记不参与授权，不能用他人资源 ID 获取其他账号头像")
    @ApiResponse(responseCode = "200", description = "本人头像二进制",
        content = {
            @Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")),
            @Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary"))
        })
    @ApiResponse(responseCode = "404", description = "未上传或资源暂不可用",
        content = @Content(schema = @Schema(implementation = R.class)))
    public ResponseEntity<InputStreamResource> avatar() {
        AssetContentData content = accountProfile.openAvatar();
        try {
            // 使用标准同步 Resource 转换器，避免异步重派发丢失当前请求的认证上下文。
            // 转换器在写入结束（包括客户端断开）后关闭该输入流；不跳过任何鉴权拦截器。
            InputStreamResource body = new InputStreamResource(content.inputStream());
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .contentLength(content.byteSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                // 同一个 URL 可能切换 Cookie 账号，浏览器/CDN 不得缓存其他账号的头像。
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(body);
        } catch (RuntimeException setupFailure) {
            try {
                content.close();
            } catch (IOException closeFailure) {
                setupFailure.addSuppressed(closeFailure);
            }
            throw setupFailure;
        }
    }

    @PutMapping("/password")
    @ApiLog
    @Operation(summary = "修改本人密码", description = "原密码精确验证，新密码沿用注册规则；"
        + "提交成功后撤销该账号全部现有会话，需要重新登录。不会修改用户名、类型或角色",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER,
            required = true, description = "会话响应中的 csrfToken"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "密码已更新且账号会话已撤销",
            content = @Content(schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "400", description = "RSA 密文无效",
            content = @Content(schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "422", description = "原密码错误或新密码规则不符合；保留现有会话",
            content = @Content(schema = @Schema(implementation = R.class))),
        @ApiResponse(responseCode = "409", description = "密码已被并发更新",
            content = @Content(schema = @Schema(implementation = R.class)))
    })
    public R<Void> password(@Valid @RequestBody AccountPasswordRequest request) {
        accountProfile.changePassword(request);
        return R.success(null);
    }

    /** 必填头像 part 缺失属于请求错误，不交给未知异常处理误报 500。 */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<R<Void>> missingAvatarPart(MissingServletRequestPartException error) {
        return ResponseEntity.badRequest().body(R.failure(ApiErrorCode.MALFORMED_REQUEST.name(),
            AccountMessageConstants.AVATAR_READ_FAILED, List.of()));
    }
}
