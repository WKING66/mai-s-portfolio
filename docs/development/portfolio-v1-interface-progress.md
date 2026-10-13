# 首版接口推进清单

更新：2026-10-07。本次按维护者新要求完成职责收敛，覆盖统一上传和既有账号/项目绑定。历史验收记录只描述当时版本；当前接口以本表及 api-responsibility-convergence-20261007 报告为准。第二阶段 Agent 与博客新功能不在本轮。

## 已有接口及证据

|能力|接口|状态/验收|
|---|---|---|
|通用登录、查询、注销|GET/POST/DELETE `/api/v1/auth/session`|已实现；project-management、frontend-auth-interceptors 验收记录|
|普通访客注册|POST `/api/v1/auth/register`|完成待审核；匿名注册、正常访客登录、拒绝管理、错误输入已真实联调。最终独立 Redis 双实例额度测试及真实 UI 注册 429→登录 200 通过；见 visitor-registration-acceptance-20261006。不代表博客导出已完成|
|网站资料|GET `/api/v1/profile`|公开和编辑页使用同一资料投影；签名地址带到期时间|
|网站资料写入|PATCH `/api/v1/profile`|页面补齐，profile-management 验收记录|
|资产信息与地址|GET `/api/v1/assets/{id}`|仅上传者或 OWNER 读取 JSON；普通资源浏览器直连私有 OSS 的签名地址|
|技术标签选择|GET `/api/v1/tags?kind=TECH`|已实现并用于项目编辑|
|统一项目列表|GET `/api/v1/projects?view=PUBLIC\|MANAGE`|已实现，公开与管理投影、权限、分页已联调|
|项目新建、读取、编辑|POST `/api/v1/projects`；GET/PATCH `/api/v1/projects/{id}`|已实现并联调|
|项目发布、下架|POST `/api/v1/projects/{id}/publish\|unpublish`|已实现并联调|
|本人资料读取/昵称修改|GET/PUT `/api/v1/account/profile`|普通用户/站长共用，账号菜单与个人中心同步，完成待审核；见 account-center-ui-acceptance-20261006|
|统一资产上传／本人头像绑定|POST `/api/v1/assets`；PUT `/api/v1/account/avatar`|AVATAR、IMAGE、ATTACHMENT 共用上传；账号绑定校验用途与上传者|
|本人修改密码|PUT `/api/v1/account/password`|验证原密码、新密码沿用注册规则，成功撤销现有会话；错误原密码不注销，已真实联调|

普通注册本轮完成待审核：后端最终 31 模块 install 成功（124 总计、84 执行、40 跳过、零失败），真实基础设施补测 14/14 通过，真实 UI 在同一个 5 分钟窗口内证明注册额度耗尽不影响正常登录。SC-022 的文章许可导出仍未实现，T069/SC-022 不能整体标为完成。

2026-10-08 已修正未提交编辑造成的注册路由漂移：注册保持 POST `/api/v1/auth/register`，会话保持 `/api/v1/auth/session`；Controller 和鉴权由同一领域前缀与相对路由常量组合。原登录页/Route 的未完成编辑仍保留，不能将它们视为已通过类型检查。具体回归与提交候选验证见 `docs/testing/auth-registration-route-fix-20261008.md`。

审核修正：登录/注册页已调整为紧凑响应式布局，注册密码 trim 后仅允许英文字母、数字、下划线与英文标点；相关测试及真实页面链路见 `docs/testing/registration-ui-password-review-20261006.md`。仍待维护者审核，不视为允许合并或继续博客开发。

## 尚未完成的首版范围

1. SEO/GEO 基础已完成首页与项目部分并真实联调，详见 `docs/testing/portfolio-seo-acceptance-20261005.md`；博客完成后仍须扩充真实公开文章。无域名配置禁止索引且不伪造正式地址。
2. System 主题标签：TOPIC 查询、创建/必要的编辑及 provider；不跨模块访问 Mapper。
3. Blog 管理：列表、新建、详情、编辑产生不可变 DocumentVersion、草稿预览、显式发布/重新发布、下架、逐篇下载许可。
4. 正常 Markdown 文件导入、Markdown 导出及统一格式接口；Word/PDF 不假装已实现。导入始终草稿；图片和文件资源处理仅预留不可用入口，报告问题，未解析归档资源不能发布。
5. 公开博客列表、详情、SSR、安全正文展示；访客必须当前有效登录且文章允许才可导出，站长不受逐篇许可影响。
6. 对新增或有改动的接口执行真实 UI 操作链及负向请求，逐项记录页面、按钮、参数、请求、状态、响应和结果；未改动且已有有效验收的行为不机械重测。

## 固定边界

PostgreSQL 唯一主库，DocumentVersion 是不可变 Markdown 事实来源；保存新版本不替换公开正文，只有显式重新发布才切换公开版本。复用三层、Service 接口/Impl、MyBatis-Plus、Request/Vo、统一异常/提示常量、敏感日志过滤及 Knife4j。不上缓存、消息系统、Agent 或无需求扩展。

按 2026-10-07 最新授权，基础文件上传与既有账号/项目绑定已纳入统一 Asset 能力。网络图片抓取、图片包归档、博客资产绑定和第二阶段检索/智能体仍未实现，不能因基础上传完成而标记这些业务完成。

历史个人中心阶段只实现本人头像；2026-10-07 已收敛为统一 Asset 上传并保留既有账号/项目绑定，博客图片绑定与文章归档仍未实现。账号下拉菜单与管理页采用 Nuxt UI。2026-10-08 按维护者要求，Pinia 账号展示快照持久化到 localStorage，刷新复用有效 session/昵称/头像；授权仍由后端完成，401/鉴权 403 更新快照，OWNER 撤销保留普通登录。首次刷新新锚点已在首帧恢复。详见 `docs/testing/frontend-persistent-state-anchor-20261008.md`。操作反馈使用 `useFeedback` 薄封装 Nuxt UI Toast，在页面上方居中、自动消失、无需确认；旧模态组件与消息管理已删除。详见 `docs/testing/nuxt-ui-toast-feedback-20261008.md`。

## Git 状态

本次职责收敛分支 `feature/api-responsibility-convergence`，父分支 `feature/backend-maintainability-fixes`，起点 `acf325e`。在独立 worktree 实施，原工作区的维护者改动保留；提交后停留供审核，不自动合并或推送。

以下为历史阶段分支记录：

功能按独立 feature 分支推进并提交/推送；未经审核不合并父分支或 main。资料管理分支父分支为 `feature/frontend-auth-interceptors`，此前功能分支尚未视为已批准合并。维护者本地非敏感代码允许提交；开发配置和凭据保持本地。

本轮注册分支 `feature/visitor-registration` 的父分支为 `feature/portfolio-seo`，起点 `4827830`；完成验证并提交/推送后停止交审核，不自动合并或继续下一业务功能。

个人中心与界面修复分支为 `feature/account-center-ui`，父分支 `feature/visitor-registration`，起点 `20d50d4`。本轮提交后停止供审核，不默认 main 为父分支，不自动合并。
