package dev.amai.portfolio.asset.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.asset.api.AssetUsage;
import dev.amai.portfolio.asset.entity.vo.AssetVo;
import dev.amai.portfolio.asset.service.AssetService;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.system.api.AuthConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(value = "/api/v1/assets", produces = "application/json")
@SaCheckLogin
@SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
@Tag(name = "资产", description = "统一文件上传；业务引用绑定由账号、项目等业务能力完成")
public class AssetController {
    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取资产信息与新的签名地址", description = "仅上传者或 OWNER 可读；不代理文件字节")
    public ResponseEntity<R<AssetVo>> resource(@PathVariable Long id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(R.success(assetService.resource(id)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiLog
    @Operation(
            summary = "上传资产",
            description =
                    "AVATAR 仅本人：PNG/JPEG，默认 2 MiB / 2048 像素；"
                            + "IMAGE 要求 OWNER：PNG/JPEG，8 MiB / 4096 像素，封面、截图和博客插图共用；"
                            + "ATTACHMENT 要求 OWNER：最多 64 MiB，以下载文件保存。图片校验重编码后上传，返回 OSS 签名 URL 与到期时间，不自动绑定、不代理字节。",
            parameters =
                    @Parameter(
                            name = AuthConstants.CSRF_HEADER_NAME,
                            in = ParameterIn.HEADER,
                            required = true))
    public R<AssetVo> upload(
            @RequestPart("file") MultipartFile file, @RequestParam AssetUsage usage) {
        // 用途授权在入口完成；上传 Service 只处理文件校验与归档。
        if (usage != AssetUsage.AVATAR) {
            StpUtil.checkRole(AuthConstants.OWNER_ROLE);
        }
        return R.success(assetService.upload(file, usage));
    }
}
