package dev.amai.portfolio.portfolio.service;

import dev.amai.portfolio.common.ViewPageVO;
import dev.amai.portfolio.portfolio.entity.request.ProjectListRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectRequest;
import dev.amai.portfolio.portfolio.entity.request.ProjectVersionRequest;
import dev.amai.portfolio.portfolio.entity.vo.ProjectEditorVo;
import dev.amai.portfolio.portfolio.entity.vo.ProjectItemVo;

/** 项目展示业务；写入口由认证层保护，统一列表的 MANAGE 视图还在服务内核对 OWNER。 */
public interface ProjectService {
    /** 公开列表只查已发布项目，管理列表返回编辑投影；分页与关联读取处于一致快照。 */
    ViewPageVO<ProjectItemVo> list(ProjectListRequest request);

    /** 读取编辑快照，供已授权的管理入口及事务内写入结果使用。 */
    ProjectEditorVo get(Long id);

    /** 创建草稿并原子写入关联，不要求草稿满足发布完整性。 */
    ProjectEditorVo create(ProjectRequest request);

    /** 完整替换文本和关联；CAS 冲突不覆盖旧值，已发布项目仍需满足发布条件。 */
    ProjectEditorVo update(Long id, ProjectRequest request);

    /** 验证内容与技术标签后发布，状态与版本在同一事务内变更。 */
    ProjectEditorVo publish(Long id, ProjectVersionRequest request);

    /** 转回草稿，不删除文件或撤销已签发的普通静态资源 URL。 */
    ProjectEditorVo unpublish(Long id, ProjectVersionRequest request);
}
