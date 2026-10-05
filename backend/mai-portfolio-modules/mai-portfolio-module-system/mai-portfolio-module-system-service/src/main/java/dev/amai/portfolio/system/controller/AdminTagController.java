package dev.amai.portfolio.system.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.system.api.TaxonomyQueryService;
import dev.amai.portfolio.system.entity.vo.TechTagOptionVo;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** System 三层目录内的只读入口，不新增嵌套标签模块或标签写接口。 */
@RestController
@RequestMapping("/api/v1/admin/tags")
@SaCheckRole(AuthConstants.OWNER_ROLE)
@Tag(name = "技术标签")
public class AdminTagController {
    private final TaxonomyQueryService taxonomy;
    public AdminTagController(TaxonomyQueryService taxonomy) { this.taxonomy = taxonomy; }

    @GetMapping
    @Operation(summary = "读取项目可选技术标签", description = "本轮仅支持 kind=TECH。",
        security = @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME))
    public R<List<TechTagOptionVo>> list(@RequestParam(defaultValue = "TECH") String kind) {
        if (!"TECH".equals(kind)) {
            throw new ApiException(ApiErrorCode.VALIDATION_FAILED,
                dev.amai.portfolio.system.constant.SystemMessageConstants.TAG_KIND_INVALID);
        }
        return R.success(taxonomy.techTagOptions().stream()
            .map(tag -> new TechTagOptionVo(tag.id(), tag.name(), tag.slug(), tag.group(), tag.logoKey())).toList());
    }
}
