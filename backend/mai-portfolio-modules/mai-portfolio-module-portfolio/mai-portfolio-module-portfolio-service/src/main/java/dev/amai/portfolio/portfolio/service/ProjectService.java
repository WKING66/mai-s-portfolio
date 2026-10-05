package dev.amai.portfolio.portfolio.service;

import dev.amai.portfolio.portfolio.entity.request.ProjectListRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectVersionRequest;
import dev.amai.portfolio.portfolio.entity.vo.AdminProjectVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectPageVo;

/** 项目展示业务；鉴权由既有 HTTP 拦截器和 Sa-Token 负责。 */
public interface ProjectService {
    ProjectPageVo list(ProjectListRequest request);
    AdminProjectVo get(Long id);
    AdminProjectVo create(ProjectRequest request);
    AdminProjectVo update(Long id, ProjectRequest request);
    AdminProjectVo publish(Long id, ProjectVersionRequest request);
    AdminProjectVo unpublish(Long id, ProjectVersionRequest request);
}
