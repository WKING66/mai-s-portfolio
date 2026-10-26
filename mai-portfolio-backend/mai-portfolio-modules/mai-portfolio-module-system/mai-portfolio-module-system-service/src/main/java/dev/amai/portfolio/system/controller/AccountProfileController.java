package dev.amai.portfolio.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.entity.request.AccountAccessRequest;
import dev.amai.portfolio.system.entity.request.AccountAvatarRequest;
import dev.amai.portfolio.system.entity.request.AccountPasswordRequest;
import dev.amai.portfolio.system.entity.request.AccountProfileRequest;
import dev.amai.portfolio.system.entity.vo.AccountProfileVo;
import dev.amai.portfolio.system.service.AccountProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = AuthConstants.ACCOUNT_PATH, produces = "application/json")
@SaCheckLogin
@SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
@Tag(name = "账号", description = "当前账号本人资料与密码；与网站公开资料分离")
public class AccountProfileController {
    private final AccountProfileService accountProfileService;

    public AccountProfileController(AccountProfileService accountProfileService) {
        this.accountProfileService = accountProfileService;
    }

    @GetMapping("/profile")
    @Operation(summary = "读取本人资料", description = "返回昵称及头像 OSS 临时地址和到期时间，不返回密码或角色配置。")
    public ResponseEntity<R<AccountProfileVo>> current() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(R.success(accountProfileService.current()));
    }

    @PutMapping("/profile")
    @ApiLog
    @Operation(
            summary = "修改本人昵称",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<AccountProfileVo> update(@Valid @RequestBody AccountProfileRequest request) {
        return R.success(accountProfileService.update(request));
    }

    @PutMapping("/avatar")
    @ApiLog
    @Operation(
            summary = "绑定本人头像",
            description = "仅接受当前账号通过 POST /assets、usage=AVATAR 创建的 READY 资产；不上传文件，不删除旧对象。",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<AccountProfileVo> avatar(@Valid @RequestBody AccountAvatarRequest request) {
        return R.success(accountProfileService.bindAvatar(request));
    }

    /** 只开放账号访问能力修改，不扩展账号列表、角色管理或另一套管理模块。 */
    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @PatchMapping("/{accountId}/access")
    @ApiLog
    @Operation(summary = "调整账号状态或类型", description = "仅站长可用；停用或改变类型后注销目标账号全部会话，后续请求需要重新登录。")
    public R<Void> updateAccess(
            @PathVariable long accountId,
            @Valid @RequestBody AccountAccessRequest request) {
        accountProfileService.updateAccess(accountId, request);
        return R.success(null);
    }

    @PutMapping("/password")
    @ApiLog
    @Operation(
            summary = "修改本人密码",
            description = "原密码精确验证，新密码使用共享政策；密码和凭据版本原子更新，旧会话失效。",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<Void> password(@Valid @RequestBody AccountPasswordRequest request) {
        accountProfileService.changePassword(request);
        return R.success(null);
    }
}
