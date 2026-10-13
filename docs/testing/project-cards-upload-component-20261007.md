# 项目卡片与复用上传组件验收

日期：2026-10-07。父分支：`feature/account-center-ui`（`8e3e70c`）；本轮分支：`feature/project-cards-upload-component`。提交后等待审核，不合并。

## 本轮范围与设计

- 按维护者截图与 Wilson Costa 项目区调整卡片：顶部封面、标题/摘要/贡献、技术标签、胶囊外链；有图与无图保留各自自然高度。没有图片时不复制别人的截图，不生成假的项目入口。
- 项目卡片每轴边缘最大倾角由约 5° 提升到约 14°，保留流动背景和指针光效。进入时固定计算平面、滚动时补偿坐标并限制边界，避免旋转后的包围盒参与反馈；离开后复位，减少动效及非精确指针保护沿用原实现。
- Storage Starter 的 `ObjectStorage` / `AliyunOssObjectStorage` 已经是通用设施，本轮复用，不再创建一套 OSS 客户端。资产建档与状态归 Asset 模块，不能塞入无数据库职责的 Storage Starter。
- Asset provider 暴露 `AssetUploadComponent`（已验证的图片/文档内容上传）与 `ImageUploadComponent`（按用途验证并重编码图片）。实现分别在 Asset 的 `component` 包。
- 通用生命周期：短事务创建 PENDING → 事务外上传 OSS → 短事务更新 READY；失败尽力记录 FAILED，返回失败而非假成功。UUID 对象键、不覆盖旧文件，不在解绑/失败时自动删除 OSS 对象。
- 原 `ImageAssetUploadService` 保留为头像业务的薄适配器，委托同一组件；原账户接口、2 MiB / 2048 像素上限与私有读取权限不变。
- 项目封面先经统一文件入口上传，保存项目时携带 `coverMediaId`。复用已有 `project_media`（role=1），关系变更与项目乐观版本更新同事务，不新增表、不修改历史 SQL。null 解除封面引用，不删除对象。
- 上传独立于项目：`POST /api/v1/admin/assets/images`；站长预览：`GET /api/v1/admin/assets/images/{assetId}`。复用 Sa-Token、账号状态、HTTPS 配置、CSRF 与前端请求拦截。
- 公开卡片使用 `GET /api/v1/projects/{id}/cover`，服务端每次校验已发布项目及当前封面关联；不允许用任意 assetId 读取文件。响应 `no-store`，不输出 OSS 密钥、对象键或签名 URL。私人头像不能作为项目封面绑定或预览。

本轮不实现 Markdown 图片包/远程图片抓取或 Agent 知识库导入流程。它们可以调用通用组件，但仍须实现各自内容校验、来源元数据及授权；不能把“有接口”视为完成业务。通用上传当前为不超过 64 MiB 的已验证内存内容，元数据来源仍按编辑器上传记录。大文件流式上传、来源拓展、失败补偿/孤儿资产治理留待对应需求确认，不声称已完成。

## 环境与数据副作用

- 浏览器始终使用 `http://127.0.0.1:3000`，代理到维护者在 IDEA 启动的 dev 后端 `9333`；没有替代端口、独立密钥、测试 owner 或密码重置。
- 维护者重启 9333 后继续联调。本轮生产构建期间只停止自有 3000 前端，随后在原端口、原 `frontend/.env` 下恢复。
- 登录凭据来自 Git 忽略的 `.local-backups/default-login.env`，不写入本文/提交，不记录密码、密文、Cookie、CSRF 或请求认证头。
- 新增验收项目 ID=2，slug=`ui-cover-20261007`，标题“验收示例：作品集封面上传”，明确声明为验收数据。最终 PUBLISHED，version=5，封面 assetId=3，供维护者直接看效果。原项目 ID=1、账户头像和公开资料未修改。
- 两次将本站真实截图上传到配置的 `amai-portfolio` bucket，生成资产 ID=2、3。最终绑定 ID=3，ID=2 保留为已解绑资产；没有删除任何 OSS 对象。新增/更新涉及 `media_asset`、`project`、`project_media`、`project_tag`，认证沿用现有 Redis 会话。
- 本地截图保存在被忽略的 `.local-backups/project-*-20261007.png`，不当成正式业务封面素材提交。

## 默认端口浏览器联调记录

工具：browser-act，独立会话 `project-cards-20261007`。界面按钮在本轮实际点击；部分负向权限检查通过该真实页面的 fetch 发出，以下明确区分。不把等待成功提示当作唯一证据，同时核对网络状态、实际页面和后续读取。

| 步骤 | 页面、点击及输入 | 请求与响应（敏感信息省略） | 页面/业务结果 |
| --- | --- | --- | --- |
| 1 | `/login`，输入维护者原账号/密码，点击“登录” | `POST /api/v1/auth/session`：200 | 进入项目管理，账户显示 owner，原头像读取 200；重启前一次请求为 502，重启后实测成功，未换环境掩盖 |
| 2 | `/admin/projects/new`，选择“项目封面”文件，上传本站 1902×984 PNG 截图 | `POST /api/v1/admin/assets/images`：200；返回 id=2、previewUrl=`/api/v1/admin/assets/images/2` | 显示“图片已上传；请保存项目完成绑定”；预览 GET 200、naturalWidth=1902，尚未创建项目 |
| 3 | 同页面输入上述 slug/标题；摘要“仅用于本轮卡片与 OSS 上传验收。封面为本站真实截图，不代表新业务项目。”；贡献“验证通用上传组件、封面绑定、发布授权与卡片光效。”；选择 Java、TypeScript、LangGraph，点击“保存项目” | `POST /api/v1/admin/projects`：200，创建 id=2，DRAFT，version=0，cover.assetId=2 | 跳转 `/admin/projects/2`，重新加载后封面仍正常；创建后页面已切换，第一次等待旧页面成功提示超时，后续网络/详情验证成功 |
| 4 | 草稿编辑页，用页面 fetch、credentials=omit 读取公开封面 | `GET /api/v1/projects/2/cover`：404 | 草稿不能公开读取，即使 OWNER 编辑页上已有预览 |
| 5 | `/admin/projects/2` 点击“发布项目” | `POST /api/v1/admin/projects/2/publish`，version=0：200，PUBLISHED，version=1 | 显示发布成功；匿名封面 GET 200、image/png、339959 字节、Cache-Control=no-store |
| 6 | `/#projects`，滚动到卡片；切换明/暗主题 | PUBLIC 列表和 `/api/v1/projects/2/cover?v=2` 图片读取成功 | 卡片宽 568px，有图卡片约454px，无图保持自然高度；顶部图片真实加载，技术标签和外链胶囊正常，桌面无横向溢出 |
| 7 | 同页在卡片左上区域发送指针事件，随后离开 | 实际组件事件处理产生 tiltX≈13.38°、tiltY≈−13.51°、glow=1 | 增强倾斜及光效生效；pointerleave 后倾角和光效变量清除。不是仅检查常量值 |
| 8 | 同页 fetch、credentials=omit 请求管理预览/列表 | GET `/admin/assets/images/2`：401；GET `/projects?view=MANAGE`：401 | 前端状态不影响后端边界（前缀均为 `/api/v1`） |
| 9 | 同页 OWNER fetch 获取私人头像作为项目图；POST multipart 不携带 CSRF | GET `/api/v1/admin/assets/images/1`：404；POST `/api/v1/admin/assets/images`：403 | 用途隔离和写入 CSRF 校验生效，未创建额外资产 |
| 10 | `/admin/projects/2` 点击“移除封面”，再点“保存项目” | PATCH 项目，version=1、coverMediaId=null：200，version=2 | 公开封面 GET 404；原站长预览 `/admin/assets/images/2` 仍200，证明解绑没有删除对象 |
| 11 | 再次选择相同截图，等待上传完成，再“保存项目” | 上传 POST 200，id=3；PATCH 项目 version=2、coverMediaId=3：200，version=3 | 新对象/资产而非覆盖 ID=2，新的预览正常 |
| 12 | 点击“下架项目”，用页面 fetch 匿名读取公开列表/封面 | POST unpublish version=3：200，version=4；封面 GET 404；PUBLIC 列表仅含原项目 ID=1 | 下一次读取立即不可见，无新增公开缓存造成泄漏 |
| 13 | 点击“发布项目”恢复验收展示，进入 `/#projects` | POST publish version=4：200；管理详情 GET 200：id=2、PUBLISHED、version=5、cover.assetId=3；匿名封面 GET 200 | 当前图 URL `.../projects/2/cover?v=3`，已正常加载；只保留明确标记的验收项目供审核 |
| 14 | 生产构建后恢复原 3000，刷新 `/#projects` | 页面和封面重新加载成功，原会话仍恢复 owner | 明色主题保持，无需另一个端口/密码；封面自然宽度非零，无横向溢出 |
| 15 | 读取真实 9333 的 `/v3/api-docs` | 新上传、管理预览、公开封面路径均存在；上传 multipart 和 ProjectRequest.coverMediaId 字段均存在 | Knife4j 使用同一 OpenAPI 定义，可查看/调试新增接口 |

## 测试与构建

- 前端 Vitest：17 文件、206 项通过（其中编辑器18项，包括封面未保存不绑定、上传失败保留原封面与输入、错误 MIME/超限不发请求）。Nuxt typecheck 与 production build 均通过，未新增依赖。
- 后端完整 Maven reactor `test` 通过，随后对 Asset/Portfolio 及依赖执行 `test -am` 验证新增测试：Asset 21项、Portfolio 17项通过；全模块 `package -DskipTests` 成功。
- 图片回归：PNG/JPEG、MIME 伪造、损坏图片、头像大小/像素限制、封面独立边界/前缀、重编码去除尾随载荷、事务外 OSS I/O、PENDING/READY 持久化失败、OSS 失败不假成功/不删对象。
- 通用文件组件：已验证 Markdown 文档可调用同一组件建档上传（mock OSS 的单元验证，不冒充实际 Markdown 导入功能）；危险命名空间写入前拒绝。
- 用途/公开授权：私人头像、文档、未就绪资产不能作为封面；草稿/未知项目拒绝公开；下架后的下一次读取拒绝；无效封面不能写项目；版本冲突不能更新媒体关系。
- 完整 reactor 中依赖专用验收环境变量的既有集成测试自动跳过，没有伪称全部端到端通过；本轮实际 OSS/页面链路以上表为准。

## 未覆盖与限制

- 本轮没有用普通账号重新走浏览器上传，也没有实际修改维护者头像；OWNER 角色检查复用既有拦截器/注解，头像行为由回归测试和原头像实际读取验证。
- 移动设备、系统真实减少动效开关、慢网/断连、并行上传压力及 OSS 故障注入未做真人浏览器验证。边界/失败逻辑有单元测试，不等同实际云故障演练。
- 新组件不会自动决定 Markdown/Agent 文件公开性、网络抓取安全或文档解析；这些必须由未来业务入口负责。
- OSS 与 PostgreSQL 不是分布式事务。失败或未保存可能保留对象/未绑定资产；当前有状态追踪，但没有自动补偿、删除或清理任务。
- 保存项目采用完整快照：新前端总会提交 coverMediaId；旧客户端不提交该字段时会按 null 移除封面，应与后端同时升级。
- 无图公开项目不会被强行补图；要展示真实项目截图，需要站长在项目编辑页上传并保存。

## 定向阅读

- `frontend/app/components/ProjectCard.vue`、`SurfaceCard.vue`、`ProjectEditor.vue`
- `frontend/app/composables/useProjectEditor.ts`、`app/api/projects.ts`、`app/constants/projects.ts`、`app/test/adminProjectInteractions.test.ts`
- Asset provider：`api/AssetUploadComponent.java`、`AssetUploadRequest.java`、`ImageUploadComponent.java`、`ImageUploadPurpose.java`、`AssetQueryService.java`
- Asset service：`component/AssetUploadComponentImpl.java`、`ImageUploadComponentImpl.java`、`service/impl/ImageAssetUploadServiceImpl.java`、`FileUploadServiceImpl.java`、`AssetQueryServiceImpl.java`、`controller/FileUploadController.java`、`entity/vo/UploadedFileVo.java`
- Portfolio service：`service/impl/ProjectServiceImpl.java`、`ProjectCoverServiceImpl.java`、`controller/PublicProjectCoverController.java`、`entity/request/ProjectRequest.java`、`entity/domain/ProjectMediaDO.java`、`mapper/ProjectMediaMapper.java`、`entity/vo/ProjectCoverVo.java`
- Asset/Portfolio 的新增及更新测试同名目录，Storage Starter 本轮复用未修改。

维护者已有的 GlobalExceptionHandler、application-dev.yaml、AuthServiceImpl 和 AccountProfileServiceImpl 改动保留且排除本轮提交；不包含凭据、运行日志、产物、截图或其他无关文件。
