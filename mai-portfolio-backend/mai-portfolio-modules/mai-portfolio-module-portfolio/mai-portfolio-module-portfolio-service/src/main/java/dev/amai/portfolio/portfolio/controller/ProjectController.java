package dev.amai.portfolio.portfolio.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.logging.annotation.ApiLog;
import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import dev.amai.portfolio.portfolio.entity.request.ProjectListRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectVersionRequest;
import dev.amai.portfolio.portfolio.entity.vo.ProjectEditorVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectPageVo;
import dev.amai.portfolio.portfolio.service.ProjectService;
import dev.amai.portfolio.system.api.AuthConstants;
import dev.amai.portfolio.validation.ValidationGroups;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 一个列表入口，两种显式视图；MANAGE 所需权限由项目服务声明并通过 System 能力核对。 */
@RestController
@RequestMapping(value = "/api/v1/projects", produces = "application/json")
@Tag(name = "项目")
public class ProjectController {
    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    @Operation(summary = "分页读取项目",
        description = "view 默认 PUBLIC，只返回已发布卡片。MANAGE 需要有效 OWNER Cookie，返回管理快照。"
            + "page 默认 1；PUBLIC size 默认 12、MANAGE 默认 20，最大 50；status 仅用于 MANAGE。")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "按视图投影的项目分页"),
        @ApiResponse(responseCode = "401", description = "MANAGE 未登录"),
        @ApiResponse(responseCode = "403", description = "MANAGE 无站长权限、账号停用或传输不安全"),
        @ApiResponse(responseCode = "422", description = "非法视图、重复参数、分页或状态")
    })
    public ResponseEntity<R<ProjectPageVo>> list(
            @Valid @ParameterObject @ModelAttribute ProjectListRequest input,
            HttpServletRequest request) {
        // 不接受重复参数，避免 MVC 将重复值静默绑定为某个视图。
        for (String name : List.of("view", "page", "size", "status")) {
            String[] values = request.getParameterValues(name);
            if (values != null && values.length != 1) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, ProjectMessageConstants.INVALID_QUERY);
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.success(projectService.list(input)));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping
    @ApiLog
    @Operation(summary = "新建项目草稿", description = "文本可未填写；标签和外链若提供则必须有效。",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public R<ProjectEditorVo> create(@Valid @RequestBody ProjectRequest request) {
        return R.success(projectService.create(request));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @GetMapping("/{id}")
    @Operation(summary = "读取项目编辑快照")
    public R<ProjectEditorVo> get(
            @Positive(message = ProjectMessageConstants.INVALID_FIELDS) @PathVariable Long id) {
        return R.success(projectService.get(id));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PatchMapping("/{id}")
    @ApiLog
    @Operation(summary = "保存项目完整快照",
        description = "须带回 version。标签、外链、封面完整替换；coverMediaId=null 移除封面引用，不删除文件。已发布项目保存立即生效且仍须满足发布条件。首次发布后标识固定。",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public R<ProjectEditorVo> update(
            @Positive(message = ProjectMessageConstants.INVALID_FIELDS) @PathVariable Long id,
            @Validated(ValidationGroups.Update.class) @RequestBody ProjectRequest request) {
        return R.success(projectService.update(id, request));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping("/{id}/publish")
    @ApiLog
    @Operation(summary = "发布项目", description = "携带 version；要求标题、摘要、贡献与技术标签，图片和外链可缺省。",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public R<ProjectEditorVo> publish(
            @Positive(message = ProjectMessageConstants.INVALID_FIELDS) @PathVariable Long id,
            @Valid @RequestBody ProjectVersionRequest request) {
        return R.success(projectService.publish(id, request));
    }

    @SaCheckRole(AuthConstants.OWNER_ROLE)
    @SecurityRequirement(name = AuthConstants.OPENAPI_SESSION_SCHEME)
    @PostMapping("/{id}/unpublish")
    @ApiLog
    @Operation(summary = "下架项目", description = "携带 version；只转回草稿，不删除项目或文件。",
        parameters = @Parameter(name = AuthConstants.CSRF_HEADER_NAME, in = ParameterIn.HEADER, required = true))
    public R<ProjectEditorVo> unpublish(
            @Positive(message = ProjectMessageConstants.INVALID_FIELDS) @PathVariable Long id,
            @Valid @RequestBody ProjectVersionRequest request) {
        return R.success(projectService.unpublish(id, request));
    }
}
