package dev.amai.portfolio.portfolio.controller;

import dev.amai.portfolio.common.R;
import dev.amai.portfolio.portfolio.constant.ProjectMessageConstants;
import dev.amai.portfolio.portfolio.entity.request.ProjectListRequest;
import dev.amai.portfolio.portfolio.entity.vo.ProjectPageVo;
import dev.amai.portfolio.portfolio.service.ProjectService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 一个列表入口，两种显式视图；MANAGE 的登录、状态和角色由既有拦截器保护。 */
@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "项目列表")
public class ProjectController {
    private final ProjectService projects;
    public ProjectController(ProjectService projects) { this.projects = projects; }

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
    public ResponseEntity<R<ProjectPageVo>> list(@ParameterObject @ModelAttribute ProjectListRequest input,
            HttpServletRequest request) {
        // 不接受重复参数，确保拦截器和 MVC 绑定看见同一个视图。
        for (String name : List.of("view", "page", "size", "status")) {
            String[] values = request.getParameterValues(name);
            if (values != null && values.length != 1) {
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, ProjectMessageConstants.INVALID_QUERY);
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.success(projects.list(input)));
    }
}
