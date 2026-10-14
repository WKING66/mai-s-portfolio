package dev.amai.portfolio.portfolio.service;

import dev.amai.portfolio.portfolio.entity.request.ProjectListRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectVersionRequest;
import dev.amai.portfolio.portfolio.entity.vo.ProjectEditorVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectPageVo;

/** 项目展示业务；鉴权由既有 HTTP 拦截器和 Sa-Token 负责。 */
public interface ProjectService {
    ProjectPageVo list(ProjectListRequest request);
    ProjectEditorVo get(Long id);
    ProjectEditorVo create(ProjectRequest request);
    ProjectEditorVo update(Long id, ProjectRequest request);
    ProjectEditorVo publish(Long id, ProjectVersionRequest request);
    ProjectEditorVo unpublish(Long id, ProjectVersionRequest request);
}
