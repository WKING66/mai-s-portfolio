package dev.amai.portfolio.portfolio.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.portfolio.entity.request.ProjectRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectVersionRequest;
import dev.amai.portfolio.portfolio.entity.vo.AdminProjectVo;
import dev.amai.portfolio.portfolio.service.ProjectService;
import dev.amai.portfolio.system.api.AuthConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只管理介绍与关联；图片先经统一文件入口上传，再随版本快照绑定，禁止项目源码上传。 */
@RestController
@RequestMapping("/api/v1/admin/projects")
@SaCheckRole(AuthConstants.OWNER_ROLE)
@SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
@Tag(name = "项目管理")
@ApiResponses({
    @ApiResponse(responseCode = "401", description = "未登录"),
    @ApiResponse(responseCode = "403", description = "无站长权限、账号停用或 CSRF 无效"),
    @ApiResponse(responseCode = "404", description = "项目不存在"),
    @ApiResponse(responseCode = "409", description = "版本冲突或标识已占用"),
    @ApiResponse(responseCode = "422", description = "字段或发布条件不满足")
})
public class AdminProjectController {
    private final ProjectService projects;
    public AdminProjectController(ProjectService projects) { this.projects = projects; }

    @PostMapping
    @ApiLog
    @Operation(summary = "新建项目草稿", description = "文本可未填写；标签和外链若提供则必须有效。",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public R<AdminProjectVo> create(@RequestBody ProjectRequest request) {
        return R.success(projects.create(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "读取项目编辑快照")
    public R<AdminProjectVo> get(@PathVariable Long id) { return R.success(projects.get(id)); }

    @PatchMapping("/{id}")
    @ApiLog
    @Operation(summary = "保存项目完整快照",
        description = "须带回 version。标签、外链、封面完整替换；coverMediaId=null 移除封面引用，不删除文件。已发布项目保存立即生效且仍须满足发布条件。首次发布后标识固定。",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public R<AdminProjectVo> update(@PathVariable Long id, @RequestBody ProjectRequest request) {
        return R.success(projects.update(id, request));
    }

    @PostMapping("/{id}/publish")
    @ApiLog
    @Operation(summary = "发布项目", description = "携带 version；要求标题、摘要、贡献与技术标签，图片和外链可缺省。",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public R<AdminProjectVo> publish(@PathVariable Long id, @RequestBody ProjectVersionRequest request) {
        return R.success(projects.publish(id, request));
    }

    @PostMapping("/{id}/unpublish")
    @ApiLog
    @Operation(summary = "下架项目", description = "携带 version；只转回草稿，不删除项目或文件。",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public R<AdminProjectVo> unpublish(@PathVariable Long id, @RequestBody ProjectVersionRequest request) {
        return R.success(projects.unpublish(id, request));
    }
}
