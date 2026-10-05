# 首版接口推进清单

更新：2026-10-06。目标是实现首版除文件上传、媒体绑定之外的接口，并完成真实前后端联调；不是只完成下表已有功能。第二阶段 Agent 不在本轮。

## 已有接口及证据

|能力|接口|状态/验收|
|---|---|---|
|通用登录、查询、注销|GET/POST/DELETE `/api/v1/auth/session`|已实现；project-management、frontend-auth-interceptors 验收记录|
|普通访客注册|POST `/api/v1/auth/register`|完成待审核；匿名注册、正常访客登录、拒绝管理、错误输入已真实联调。最终独立 Redis 双实例额度测试及真实 UI 注册 429→登录 200 通过；见 visitor-registration-acceptance-20261006。不代表博客导出已完成|
|公开资料|GET `/api/v1/public/profile`|已实现，SSR 与保存后同步验证|
|站长资料读写|GET/PATCH `/api/v1/admin/profile`|页面补齐，profile-management 验收记录|
|既有绑定图片只读代理|GET `/api/v1/public/profile/media/{assetId}`|既有能力保留，不扩展上传或绑定|
|技术标签选择|GET `/api/v1/admin/tags?kind=TECH`|已实现并用于项目编辑|
|统一项目列表|GET `/api/v1/projects?view=PUBLIC\|MANAGE`|已实现，公开与管理投影、权限、分页已联调|
|项目新建、读取、编辑|POST `/api/v1/admin/projects`；GET/PATCH `/api/v1/admin/projects/{id}`|已实现并联调|
|项目发布、下架|POST `/api/v1/admin/projects/{id}/publish\|unpublish`|已实现并联调|

普通注册本轮完成待审核：后端最终 31 模块 install 成功（124 总计、84 执行、40 跳过、零失败），真实基础设施补测 14/14 通过，真实 UI 在同一个 5 分钟窗口内证明注册额度耗尽不影响正常登录。SC-022 的文章许可导出仍未实现，T069/SC-022 不能整体标为完成。

## 尚未完成的首版范围

1. SEO/GEO 基础已完成首页与项目部分并真实联调，详见 `docs/testing/portfolio-seo-acceptance-20261005.md`；博客完成后仍须扩充真实公开文章。无域名配置禁止索引且不伪造正式地址。
2. System 主题标签：TOPIC 查询、创建/必要的编辑及 provider；不跨模块访问 Mapper。
3. Blog 管理：列表、新建、详情、编辑产生不可变 DocumentVersion、草稿预览、显式发布/重新发布、下架、逐篇下载许可。
4. 正常 Markdown 文件导入、Markdown 导出及统一格式接口；Word/PDF 不假装已实现。导入始终草稿；图片和文件资源处理仅预留不可用入口，报告问题，未解析归档资源不能发布。
5. 公开博客列表、详情、SSR、安全正文展示；访客必须当前有效登录且文章允许才可导出，站长不受逐篇许可影响。
6. 对新增或有改动的接口执行真实 UI 操作链及负向请求，逐项记录页面、按钮、参数、请求、状态、响应和结果；未改动且已有有效验收的行为不机械重测。

## 固定边界

PostgreSQL 唯一主库，DocumentVersion 是不可变 Markdown 事实来源；保存新版本不替换公开正文，只有显式重新发布才切换公开版本。复用三层、Service 接口/Impl、MyBatis-Plus、Request/Vo、统一异常/提示常量、敏感日志过滤及 Knife4j。不上缓存、消息系统、Agent 或无需求扩展。

上传、媒体绑定、网络图片抓取、图片包归档和第二阶段检索/智能体明确暂缓。含这些范围的历史任务不能因正文接口完成而整项标为完成。

## Git 状态

功能按独立 feature 分支推进并提交/推送；未经审核不合并父分支或 main。资料管理分支父分支为 `feature/frontend-auth-interceptors`，此前功能分支尚未视为已批准合并。维护者本地非敏感代码允许提交；开发配置和凭据保持本地。

本轮注册分支 `feature/visitor-registration` 的父分支为 `feature/portfolio-seo`，起点 `4827830`；完成验证并提交/推送后停止交审核，不自动合并或继续下一业务功能。
