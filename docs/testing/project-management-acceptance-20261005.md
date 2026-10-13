# 项目管理与展示：实际联调记录

日期：2026-10-05。分支：`feature/project-management-display`；父分支：`main`。

本文件是本轮已执行操作的记录，不是仅供未来执行的测试计划。验收使用真实浏览器、Nuxt 服务和 Spring Boot 服务。密码、Cookie、CSRF 和 RSA 密文不记录。

## 环境与隔离

- 前端：`http://127.0.0.1:3004`（Nuxt 生产构建，本地 HTTP）。
- 后端：`http://127.0.0.1:19334`（dev）；Knife4j：`http://127.0.0.1:19334/doc.html`。
- PostgreSQL：现有 `portfolio_dev` 中独立 schema `project_acceptance_20261005`，不修改 public 数据。
- Redis：现有本地服务，项目键前缀 `mai-portfolio:project-acceptance:20261005`。
- `project_owner` / `project_normal` 是新建验收账号；随机密码保存在被忽略的本地夹具文件。无注册接口、无正式账号密码变更。
- 继续使用真实 OSS 配置，但本轮不上传、绑定或删除对象；不操作 Docker，不终止已有的 3002/19333 服务。
- 已有 Maven 仓库包含旧版本项目 JAR；临时运行 classpath 排除这些旧 JAR，使用本次构建类目录，避免被已删除的旧认证类污染。未修改项目依赖来绕过问题。

## 已执行的页面主链路

### 登录与管理入口

1. 打开 `/login`；用户名输入 `project_owner`，密码输入隔离夹具密码；点击“登录”。
2. 浏览器请求 `POST /api/v1/auth/session`：200，`code=OK`，`loggedIn=true`、`username=project_owner`、`roles=[OWNER]`（CSRF 脱敏）。
3. 登录后查询 `GET /api/v1/auth/session`：200，相同角色；跳转 `/admin/projects`。
4. 页面显示“项目管理”“公开首页”“项目栏目”“注销”“新增项目”；请求 `GET /api/v1/projects?view=MANAGE&page=1&size=20`：200，`view=MANAGE,page=1,size=20,total=0,items=[]`。

### 首个项目草稿

1. 管理列表点击“新增项目”，进入 `/admin/projects/new`。
2. `GET /api/v1/admin/tags?kind=TECH`：200，返回技术标签选项，Java 为 id=1、Vue 为 id=6；未提供标签写入接口。
3. 输入：slug=`acceptance-agent`；标题=`验收项目 A · AI Agent`；摘要=`工具权限与预算约束的 Agent 项目展示。`；本人贡献=`独立设计后端接口、工具权限和性能评估。`；勾选 Java 和“首页重点”；排序=20。图片、成果、时间、外链留空。
4. 点击“保存项目”：`POST /api/v1/admin/projects`，请求字段为上述值，`tagIds=[1],links=[],featured=true,sortOrder=20`；写请求携带 CSRF（脱敏）。
5. 返回 200/OK：`id=1,status=DRAFT,version=0,publishedAt=null`；导航 `/admin/projects/1`。
6. 自动读取 `GET /api/v1/admin/projects/1`：200，内容和版本与保存结果一致；编辑页面显示“草稿 · 版本 0”。
7. 匿名 `GET /api/v1/projects?size=3`：200，`view=PUBLIC,total=0,items=[]`，证明新建草稿不会自动公开。

### 四个项目发布、排序、更新与下架

1. 编辑 A 页面点击“发布项目”：`POST /api/v1/admin/projects/1/publish`，`{version:0}` → 200/OK，`PUBLISHED,version=1`。
2. 重复通过列表“新增项目”及编辑页“发布项目”创建并发布下表数据。全部创建返回 200/DRAFT/version=0，发布返回 200/PUBLISHED/version=1；字段与表中输入一致。成果、时间、图片与外链均空。

|ID|slug|标题|摘要|本人贡献|标签 ID|重点|排序|
|---|---|---|---|---|---|---|---|
|1|acceptance-agent|验收项目 A · AI Agent|工具权限与预算约束的 Agent 项目展示。|独立设计后端接口、工具权限和性能评估。|1|true|20|
|2|acceptance-portfolio|验收项目 B · Portfolio|个人作品集与权限隔离。|实现 Nuxt 展示与 Spring Boot 管理接口。|6|true|10|
|3|acceptance-search|验收项目 C · Search|搜索项目展示。|实现搜索查询与索引。|18|false|0|
|4|acceptance-ci|验收项目 D · CI|持续交付项目展示。|搭建自动构建与部署流程。|13|false|0|

3. 点击“公开首页”：`GET /api/v1/projects?view=PUBLIC&page=1&size=3` → 200，`total=4,items.id=[2,1,4]`。DOM 实际只有 B、A、D 三张卡片；重点优先、重点内部排序升序、普通项目同序时 ID 降序；有“查看更多项目 →”，href=`/projects`。
4. 点击“查看更多项目 →”，等待稳定：`GET /api/v1/projects?view=PUBLIC&page=1&size=12` → 200，`total=4,items.id=[2,1,4,3]`，栏目四张卡片。站长仍已登录，公开响应不含 status/version/featured/sortOrder/publishedAt/updatedAt。
5. 管理列表点击 A 的“编辑项目”，将摘要修改为 `已发布项目更新：新增工具审计与预算验证。`，点击“保存项目”。`PATCH /api/v1/admin/projects/1` 携带原 version=1 和完整快照 → 200，仍 PUBLISHED、version=2，摘要更新。
6. 未保存修改时“下架项目”按钮 disabled=true；首次发布后 slug 输入 readonly=true。
7. 点击“项目栏目”，等待稳定：公开 A 卡片立即显示新摘要，无需重新发布。
8. 管理列表进入 D 的编辑页点击“下架项目”：`POST /api/v1/admin/projects/4/unpublish`，`{version:1}` → 200；页面提示“项目已下架，资料仍然保留。”，DRAFT/version=2。
9. 下一次真实浏览器公开读取立即不含 D，前三自动由 C 补齐 `[2,1,3]`；不经过新增公开缓存。

### 栏目实际分页

四条数据不会产生第二页，因此另外通过同一浏览器的真实 HTTP API 补充 9 条隔离夹具（**不是逐条 UI 新建**）：n=5..13，slug=`acceptance-page-{n}`，title=`Pagination fixture {n}`，summary=`Public project pagination acceptance`，contribution=`Acceptance fixture`，tagIds=[1]，links=[]，featured=false，sortOrder=100+n。创建、发布共 18 个请求均返回 200，ID=5..13。

1. D 尚未下架时，刷新 `/projects`：共 13 条，DOM 卡片数=12，显示“下一页”。
2. 点击“下一页”：URL=`/projects?page=2`，公开请求参数 PUBLIC/page=2/size=12，200；页面只有 `Pagination fixture 13` 一张卡片，显示“第 2 页 · 共 13 个项目”和“上一页”。
3. 点击“上一页”：URL=`/projects?page=1`，200；DOM 卡片数恢复为 12。公开查询键随 page 变化，不复用管理查询。

## 真实 HTTP 边界请求（浏览器同源 fetch，非按钮操作）

负向参数没有对应的合法页面按钮，本节明确记录手工请求；其余主链路与错误恢复均使用真实页面。

|接口/参数|状态码 / 业务码|实际结果|
|---|---|---|
|GET /api/v1/projects?view=INVALID|422 / VALIDATION_FAILED|项目视图、状态或分页参数不正确|
|GET /api/v1/projects?view=PUBLIC&view=MANAGE|422 / VALIDATION_FAILED|拒绝重复参数，不进入管理查询|
|GET /api/v1/projects?view=PUBLIC&size=51|422 / VALIDATION_FAILED|服务端上限 50|
|GET /api/v1/projects?view=PUBLIC&status=DRAFT|422 / VALIDATION_FAILED|不能用公开视图筛选草稿|
|PATCH /api/v1/admin/projects/1，正确版本，CSRF=invalid|403 / CSRF_INVALID|缺少有效的写入令牌；未更改项目|
|PATCH /api/v1/admin/projects/1，version=0、其余为当前完整快照|409 / DATA_CONFLICT|拒绝覆盖新版本|
|PATCH /api/v1/admin/projects/1，tagIds=[999999]|422 / VALIDATION_FAILED|拒绝不存在/非技术标签|
|PATCH /api/v1/admin/projects/1，CODE 外链 javascript:alert(1)|422 / VALIDATION_FAILED|拒绝非 HTTP(S) 外链|
|POST /api/v1/admin/projects/4/publish，version=1（实际已=2）|409 / DATA_CONFLICT|旧版本不能重新发布|
|GET /api/v1/admin/tags?kind=TOPIC|422 / VALIDATION_FAILED|选择接口仅 TECH|

两个浏览器同源请求并发 PATCH A，均带 version=2，分别 outcome=`Concurrent winner 1/2`，其余完整快照不变：一项 200、另一项 409。最终 version=3、outcome=`Concurrent winner 1`、tags=[1]；失败事务没有清除关联。未以单元测试替代这个数据库并发验证。

## 页面错误恢复与权限变更

### CSRF 不误注销

编辑 A 页中填写 outcome=`CSRF UI recovery`；验收临时拦截仅一次 PATCH 的 CSRF 头为 invalid，点击“保存项目”。捕获 403/CSRF_INVALID；页面仍在 `/admin/projects/1`，管理入口仍在，输入保留；提示“安全令牌已更新，请重新提交本次操作。”。前端查询会话刷新令牌。再次点击“保存项目” → 200、version=4。

### 409 保留输入

编辑 A 页读到 version=4 后，另一次同源 API 修改 outcome=`External concurrent edit` → 200/version=5。页面填写 outcome=`Keep my unsaved conflict text`，点击“保存项目” → 409；提示版本冲突，文本仍在，没有自动重试或覆盖。点击“重新读取” → GET 200，显示 `External concurrent edit`，错误清除。

### 撤销角色与停用账号

- 仅在隔离 schema 将 project_owner 的 type 改为普通账号；已打开 A 编辑页点击“保存项目” → 403/FORBIDDEN。前端 GET 会话取得 roles=[]，清空表单与管理状态并跳转 `/forbidden`，DOM 中管理输入数=0。页面显示“无访问权限”“返回首页”“切换账号”。
- 恢复该验收账号 OWNER，直接打开 `/admin/projects/1` → 会话查询恢复权限，出现原项目标题，不依赖 localStorage。
- 在隔离 schema 将该账号 status=0，当前编辑页点击“保存项目” → 403/FORBIDDEN，刷新会话仍被拒绝；前端清空角色/CSRF/表单并进入 `/login`，显示“账号不存在或已停用”，管理输入数=0。随后恢复验收账号 enabled/OWNER。正式账号没有变更。

### 普通账号、篡改客户端角色、匿名

1. 注销旧验收站长会话；登录页输入 project_normal 和隔离密码，点击“登录” → POST 200；GET 会话 `loggedIn=true,roles=[]`。页面显示“已登录”“注销”“返回首页”，没有管理入口。
2. 普通账号直接请求 MANAGE 列表和 admin 项目详情 → 均 403/FORBIDDEN；输入 `/admin/projects` → `/forbidden`，不渲染管理内容。
3. 普通账号公开首页有 3 张卡片，无管理导航。验收时将 Nuxt 内存中的 roles 临时改为 [OWNER]，导航可以暂时伪造显示；请求 MANAGE → 403/FORBIDDEN；携带普通账号有效 CSRF 的 unpublish 写请求也 → 403/FORBIDDEN。点击伪造的“管理项目”导航时，守卫重新查询服务端角色，仍进入无权限页。localStorageKeys=[]。
4. “切换账号”到登录页，点击“注销” → DELETE /api/v1/auth/session 200。随后 GET 会话为 `loggedIn=false,roles=[]`；Nuxt auth 状态 csrfToken=null、管理缓存键为空。
5. 匿名默认 GET /api/v1/projects → 200/PUBLIC，仅公开投影字段：id/slug/title/summary/contribution/outcome/timeLabel/tags/links；MANAGE、admin 详情、TECH 标签选项 → 均 401/UNAUTHENTICATED。
6. 匿名直接打开 `/admin/projects/new` → `/login?returnTo=/admin/projects/new`；验证完成前没有管理表单或管理资料请求。

### SSR 与接口边界

实际读取 Nuxt 首页原始 HTML：data-project-card 数=3，下架 D 的标题不存在，MANAGE 投影、DRAFT 状态、version 字段均不存在。公开读取不随站长 Cookie 自动扩大范围。

真实 `/v3/api-docs` 只有以下项目/标签接口：GET `/api/v1/projects`、POST `/api/v1/admin/projects`、GET/PATCH `/api/v1/admin/projects/{id}`、POST publish/unpublish、GET `/api/v1/admin/tags`。不存在 admin/public 双列表，也未增加上传、媒体绑定或网络抓取接口。

## 构建与补充测试

- 后端全 31 模块 Maven verify：成功；报告 94 个测试，失败/错误=0，其中 34 个需要显式环境开关的既有集成测试跳过（即实际执行 60 个）。本轮真实服务与浏览器联调另行记录于上文，不能把跳过项算成通过。
- 为避免干扰既有运行实例，本轮 verify 跳过 Spring Boot 可执行 JAR 的 repackage，未跳过编译、普通打包和测试；真实后端运行的是本次模块 classpath。
- 新增服务层边界测试：非法视图、分页上限、公开状态筛选、重复/未知标签、不安全外链、旧版本不触碰关联；新增中性路径授权测试：PUBLIC 默认、MANAGE 账号/角色、生产 HTTP 不能伪造 HTTPS。
- 前端 typecheck 成功；Vitest 22/22 通过；Nuxt 生产构建成功。

## 最终构建复核与验收结果

加载最终构建后，又完成以下实际操作（没有用原实例的旧认证代码验收）：

- 匿名管理路由回跳登录后输入 project_owner，登录成功，回到指定 `/admin/projects/new`。不填任何字段点击“保存项目” → POST 200，id=14/DRAFT/version=0；点击“发布项目” → 422/VALIDATION_FAILED，页面提示“发布需填写标题、摘要、本人贡献和至少一个技术标签”。
- 填标题=`可选标识验收`、摘要=`只验证计划要求的发布条件。`、贡献=`功能验收。`，勾选 Java；slug、图片、外链仍空。点击保存 → PATCH 200/version=1，点击发布 → POST 200/PUBLISHED/version=2，之后点击下架 → 200/DRAFT/version=3。没有超出计划追加标识必填门槛。
- 在已打开的 id=14 编辑页，通过同源 API 注销服务端会话而暂时保留客户端状态；点击“保存项目” → PATCH 401/UNAUTHENTICATED；导航 `/login?returnTo=/admin/projects`，管理表单消失，出现登录控件。验证 401 的清理链路不是只测匿名路由。
- 再次登录恢复 OWNER。仅下架 5..13 的分页夹具，9 次 unpublish 都 200；没有删除数据、关系或对象文件。
- 给 B 的完整快照增加两条外链：CODE/GitHub/`https://github.com/WKING66`/visible=true/sort=0；OTHER/Hidden/`https://example.com/private-acceptance`/visible=false/sort=1。PATCH 200/version=2，管理 DTO links=2；PUBLIC DTO 仅返回 GitHub 一条，字段只有 type/label/url，不回传 visible/sortOrder。页面没有隐藏外链。
- OWNER `GET /api/v1/projects?view=MANAGE` → 默认 size=20，total=14；GET 不存在的项目 999999 → 404/NOT_FOUND。
- 管理列表“状态筛选”选择“草稿” → `GET /api/v1/projects?view=MANAGE&page=1&size=20&status=DRAFT` 200，页面显示 11 个草稿，已发布 A/B/C 不混入。
- 公开页面只有 A/B/C 共 3 条时，DOM 仍是三张卡片，“查看更多”不存在。随后通过 D 编辑页点击“发布项目” `{version:2}` → 200/PUBLISHED/version=3；公开首页恢复 B/A/D 三张，GitHub 入口有效，“查看更多”重新出现。最终公开总数为 4，分页夹具及 id=14 保持草稿。
- 真实 OpenAPI 的 view enum、page 最小值、size 最大值和 ProjectRequest.version 描述已读取核对；不是仅在源码中增加注释。
- 实际日志文件存在；检查两份隔离账号密码均未出现在日志，encryptedPassword 与 csrfToken 为 `[REDACTED]`。没有把原始认证请求头/响应令牌写入本验收记录。
- 深色截图检查通过，无图卡片采用文本布局；真实 hover/角落 pointer 事件得到 tilt-x≈4.72deg、tilt-y≈-4.73deg、glow=1，连续背景动画为 surface-flow。仅在验收脚本临时模拟 matchMedia 的减少动效标志，指针移动未更新倾斜；这不是系统级辅助设置实测。原始 document.title 已确认是首页标题（浏览器工具的 state 标题缓存会滞后一页）。

|接口|页面/主要动作|正向结果|负向结果|
|---|---|---|---|
|POST /api/v1/auth/session|登录页，输入验收账号，点击登录|OWNER 和普通账号均 200；角色分别 [OWNER]/[]|不改现有 RSA/Cookie 方案|
|GET /api/v1/auth/session|登录恢复、进入管理路由、权限错误刷新|登录/匿名均 200；角色最新|停用账号 403|
|DELETE /api/v1/auth/session|登录页/管理导航“注销”|200，清空角色、CSRF 和管理状态|失效后写入 401|
|GET /api/v1/projects PUBLIC|首页、查看更多、栏目上一页/下一页|200；首页 3、栏目 12；无管理字段|非法/重复参数、超上限、公开草稿筛选 422|
|GET /api/v1/projects MANAGE|管理列表、草稿筛选|200；默认 20、含状态/版本|匿名 401；普通/撤权 403；生产 HTTP 单元测试拒绝|
|GET /api/v1/admin/tags?kind=TECH|新增/编辑页自动加载标签|200；仅选择，无写入功能|匿名 401；TOPIC 422|
|POST /api/v1/admin/projects|新增页“保存项目”|200；默认 DRAFT/version=0|受登录、OWNER、CSRF 边界保护|
|GET /api/v1/admin/projects/{id}|列表“编辑项目”、编辑页“重新读取”|200；完整版本快照|匿名 401；普通 403；不存在 404|
|PATCH /api/v1/admin/projects/{id}|编辑页“保存项目”|200；公开立即更新、关联同事务|401/403/409/422，输入保留或按权限清理|
|POST /api/v1/admin/projects/{id}/publish|编辑页“发布项目”|200；条件齐备，无图/无外链可发布|必填不齐 422；旧版本 409|
|POST /api/v1/admin/projects/{id}/unpublish|编辑页“下架项目”|200；下一次公开读取消失|普通账号有效 CSRF 仍 403|

验收结论：本轮项目管理与展示的主链路、角色边界、公开隔离、错误恢复和分页已通过真实服务/浏览器联调；尚未合并父分支。

## 交付边界与未验证事项

- 不实现项目删除、文件上传/媒体绑定/远程图片抓取、博客或 Agent；保留现有资料、文件访问、RSA、HttpOnly Cookie、CSRF、Sa-Token、Redis 限流和既有异常/日志体系。未新增缓存、认证协议或业务模块。
- 本地 dev HTTP 是明确允许的。生产 HTTPS 对 MANAGE 的边界通过拦截器测试验证，但没有公网域名、证书或代理部署，不能将其描述为已完成部署验收。
- 没有压测、跨进程故障注入、实际系统级“减少动态效果”设置和移动设备实测；卡片复用现有 A 版 SurfaceCard/CSS 的动效与减少动效适配。既有 34 项受开关控制的集成测试没有全部启用。
- 项目编辑是完整快照替换，不是字段级部分 PATCH；关联失败通过事务回滚，冲突不自动重试。正式库没有新增项目验收数据。
- 验收 schema、Redis 命名空间和随机账号夹具保留供审核，没有删除；前后端 3004/19334 仅供本轮验收，不使用正式内容。管理测试账号为 project_owner，密码由维护者在被忽略的 `.local-backups/project-acceptance.env` 查阅，绝不提交到 Git。
- 保留维护者原有 `application-dev.yaml` 修改，文件哈希始终为 F9B83E771118A1B4436E8754B2F4747445EDE978B702AEC02E67E5CEBD50956F；不纳入本轮提交。环境文件、日志、构建产物、浏览器缓存及截图不提交。

## 定向阅读清单

- Portfolio：`service/ProjectService.java`、`service/impl/ProjectServiceImpl.java`；`controller/ProjectController.java`、`AdminProjectController.java`；`mapper/ProjectMapper.java`、`ProjectTagMapper.java`、`ProjectLinkMapper.java`；Project*Request、PublicProject*Vo、AdminProjectVo、ProjectPageVo、Project*DO 与常量/枚举。
- System：`auth/ProjectViewAccess.java`、`AuthenticationInterceptor.java`、`AdminAuthorization.java`；`service/impl/AuthServiceImpl.java`、`TaxonomyQueryServiceImpl.java`；`controller/AdminTagController.java`、SessionVo/TechTagOptionVo；provider 的 AuthConstants/TaxonomyQueryService/TechTagOptionData。
- 前端：`app/composables/useAuthState.ts`、`app/middleware/owner.ts`、`app/api/{permissions,projects,request,session}.ts`；ProjectCard/ProjectEditor/AdminNavigation/SiteHeader；pages/index.vue、projects.vue、login.vue、forbidden.vue、admin/projects 下三个页面；constants/projects.ts。
- 测试：ProjectServiceImplTest、ProjectViewAuthorizationTest、ProjectAcceptanceFixtures；前端 permissions.test.ts/request.test.ts 及已有 session/loginCrypto 测试。
- 本轮数据库表：project、project_tag、project_link、tag、user_account；查询 site_config 供首页。不新增迁移脚本或表。写事务先用 id+version 原子更新项目，再替换关联；只删除旧关联行，不删项目和文件。
