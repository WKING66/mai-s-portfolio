# Pinia 共享快照、下拉抖动与刷新锚点验收

日期：2026-10-07。在待审核功能分支 `feature/account-center-ui` 继续修复，父分支仍为 `feature/visitor-registration`（原起点 `20d50d4`）；本次基于 `0a092cc`。没有合并父分支或 main，没有使用 ponytail。

> 后续修正：本文是当时独立端口/账号的历史验收记录，不能代表维护者默认开发环境。本轮在 3000 dev 复现启动依赖循环和刷新锚点丢失，已修复；当前默认环境结果及新的初始滚动策略以 [默认端口验收](default-port-auth-navigation-20261007.md) 为准。

## 本次需求与实现边界

- 普通下拉选择器打开/选择时不得使页面横向抖动。检查现有两个 USelect 和两个菜单；普通选择器通过 Nuxt UI 的 UTheme 统一设置 `content.bodyLock=false`，账号/移动导航菜单保留 `modal=false`。不修改第三方组件源码，不用全局覆盖 padding 隐藏问题。
- 首次载入/整页刷新由浏览器恢复锚点或历史滚动，不主动回顶部；站内栏目导航仍可平滑滚动，减少动效时即时定位。取消全局 CSS smooth，避免恢复滚动被动画化。
- 共享业务数据迁入 Pinia：会话/本人资料、公开作品集响应、站长管理快照分别保存在 `account`、`portfolio`、`management` 三个 Store。仅内存保存权限、CSRF 和管理快照，不使用 localStorage 持久化这些数据，不读取 HttpOnly Cookie。
- 每次整页启动恢复会话一次，并共享同一在途请求。普通切页读取已有快照；登录直接使用 POST 返回的会话，不追加 GET。主动重试、真实 FORBIDDEN、CSRF_INVALID 才重新查会话；一般业务 403 不刷新权限、不误注销、不自动重放写入。
- 角色撤销确认后尝试注销，并返回登录；401 清空状态。注销失败时也清空失效 UI 权限，但不能宣称服务端注销已成功。旧查询/旧请求不能覆盖后来登录的账号。
- 已读取的本人资料、技术标签、项目详情/分页与站长公开资料复用；成功更新后接受新快照，失效相关公开/列表缓存。“重新读取”强制访问服务器；发生冲突时保留表单，用户可主动读取最新版本。
- 首页公开查询并行；SSR 请求完成后将公开响应写入本请求的 Pinia，水合复用 SSR 数据，不先清空内容再重取。公开缓存与管理缓存分离；旧公开响应不能在本次更新失效后重新回填。

依据：使用 [Pinia 官方 Nuxt 集成方式](https://pinia.vuejs.org/ssr/nuxt.html)，安装固定版本 `pinia=4.0.3`、`@pinia/nuxt=1.0.2`。真实项目构建和测试是版本兼容性的验收依据。SSR 各请求显式使用当前 NuxtApp 的 Pinia 实例，不以模块级单例保存用户状态；Promise/在途 Map 不进入可序列化 Store state。

本次只改前端及文档。没有修改后端接口、权限拦截器、认证协议、数据库迁移或启动配置。维护者已有三个后端修改文件保持原样、排除提交。没有进行新的项目/昵称/密码/头像业务写入，也没有上传、删除文件。

## 真实浏览器链路记录

使用 browser-act 的本轮独立会话，最终 Nuxt 生产构建预览 `http://127.0.0.1:3004`，既有隔离后端 `http://127.0.0.1:19334`。沿用 PostgreSQL 专用验收 schema 与 Redis 前缀，不操作 Docker。下列记录只包含路径、方法、状态与几何位置；不记录密码、Cookie、CSRF 或密文。

### 1. 下拉框与菜单

|页面 / 实际操作|检查值|结果|
|---|---|---|
|`/admin/projects` 点击“状态筛选”，打开后按 Escape 关闭|页头容器 left、width；scrollY；body padding-right/overflow|打开前后均 left=343.5px、width=1200px、scrollY=0、padding=0px；打开时 overflow=visible，没有额外补宽|
|`/admin/projects/15` 滚动到“外部入口”，点“添加外部入口”|只修改未保存表单，不点“保存项目”|新增本地入口行；未发送项目写请求，项目仍为版本 4 草稿|
|点击“入口类型”下拉框|控件 left/top/width、scrollY、body padding|打开前/后均 left=408.5px、top=522.625px、width=527px、scrollY=1067、padding=0px；overflow=visible|
|选择“在线演示”|同上及选中显示文字|上述坐标/尺寸/滚动位置完全不变；文本更新为“在线演示”；未产生 HTTP 请求|
|从账号菜单离开编辑页，后来返回同一项目|未保存入口不应污染已保存 Store|编辑页恢复已保存版本，没有刚才未保存的外部入口；不需重取详情和标签|

### 2. 会话与共享数据请求次数

登录页已完成本次应用的初始会话恢复后，开启仅路径/方法/状态的请求观测，用隔离账号 `project_owner` 点击“登录”。密码从被忽略的验收文件载入，未输出。

实际操作顺序：登录 → 管理列表 → 项目 15 编辑 → 账号菜单“个人中心” → 菜单“管理项目” → 管理导航“站长公开资料” → “项目管理” → 同一项目 15 编辑。

全链路新增请求只有：

|接口|新增次数|状态 / 页面结果|
|---|---:|---|
|POST `/api/v1/auth/session`|1|200；进入管理列表，不追加 GET 会话|
|GET `/api/v1/auth/session`|0|上述普通切页全程未重新查询；初次应用恢复不计入此段|
|GET `/api/v1/account/profile`|1|200；后续个人中心和菜单复用同一份资料|
|GET `/api/v1/projects?view=MANAGE&page=1&size=20`|1|200；两次返回管理列表复用 Pinia 快照|
|GET `/api/v1/admin/projects/15`|1|200；返回编辑页复用已保存详情，未保存表单没有写入快照|
|GET `/api/v1/admin/tags?kind=TECH`|1|200；Java 等标签持续存在，不因切页重复请求|
|GET `/api/v1/admin/profile`|1|200；首次进入公开资料编辑页读取|

普通访问首页 `/#projects`：浏览器资源记录中 GET 会话为 1 次，公开 profile/projects 的客户端重取为 0 次；公开内容来自 SSR。根主题为 dark，项目锚点 top=69.53125px、scrollY=1353。

### 3. 延迟会话 + 整页刷新

使用被忽略的本地验收转发器 3005 转发真实 3004 生产页面，仅在 head 注入滚动/请求观测并让浏览器 GET 会话等待 2500ms。未更改生产代码或后端响应内容。采样时钟起点为该 head 脚本执行时刻，以下数值不是含网络 TTFB 的整页加载耗时。

- 访问 `http://127.0.0.1:3005/#blog`：678 次逐帧样本；约 141ms 已定位 scrollY=2422、锚点 top=69.53125px；之后至约 5006ms，scrollY 最小/最大均为 2422。会话在约 2779ms 返回 200，本人资料约 2822ms 返回 200；没有客户端公开数据重取，没有因会话返回发生回顶/回锚点。
- 最终构建上整页刷新同一 URL：529 次样本；约 35ms 已定位同一位置，后续至约 4244ms 最小/最大仍均为 2422。会话约 2694ms、本人资料约 2727ms 返回 200；内容未被清空。
- 工具的一次自动权限审核等待曾超时，失败的角色篡改测试命令没有执行；拆成简短步骤重试后实际执行成功，见下一节。没有把超时尝试记为验收通过。

### 4. 注销与服务端最终授权

1. 回到真实 3004 `/account`，点击账号菜单“退出登录”：回首页，入口“登录”。读取真实 Pinia 的非敏感状态，`loggedIn=false`、本人 `profile=null`、管理详情数为 0；Store 列表为 account/management/portfolio。
2. 专用浏览器已无登录 Cookie 后，只篡改 Pinia UI 快照为 `loggedIn=true, roles=['OWNER']`，不更改 Cookie/数据库，不注入 CSRF。
3. 点击伪造 UI 显示的“管理项目”：真实 GET `/api/v1/projects?view=MANAGE&page=1&size=20` 返回 **401**，未返回管理数据；统一拦截器清空伪造状态并进入登录页。最后读取 Pinia：`loggedIn=false`、管理详情数 0。

本次没有真实修改服务器账号状态/角色。因此“角色撤销后尝试注销”“注销失败/新登录竞态”“CSRF 错误不误注销”由下述受控测试验证，不冒充真实数据库停用/角色撤销联调。

## 测试与构建

- Vitest：16 个文件、**198/198 通过**。
- Store 测试：账号共享及资料清理、SSR 实例隔离、标签去重、详情复用/强制读取、写后失效、旧响应不回填、失败可重试。
- API 缓存测试：SSR 完成后入库、水合回退到 Nuxt payload、MANAGE 不能写入 PUBLIC 缓存、更新后公开/列表失效、旧公开请求不回填、共享只读请求不重取。
- 认证测试：初始恢复一次、登录快照不重复查会话、401/403、CSRF、不自动重放、撤销角色后退出、退出失败清理 UI、退出期间新登录不被清理。
- 滚动测试：首次进入/刷新不干预原生位置、历史位置即时恢复、站内锚点滚动、减少动效、同页查询及跨页顶部策略。
- Nuxt typecheck 通过；最终生产 build 通过（4.79 MB）。构建存在既有第三方包 exports 弃用警告，不是构建失败，本轮没有顺手升级其他依赖。

## 失效策略与限制

- Pinia 是当前应用内展示快照，不是授权依据。页面初始化/刷新仍需一次会话恢复，因为前端不能读取 HttpOnly 登录 Cookie；普通切页不再查。
- 未增加账号轮询、WebSocket 或跨标签页同步。别的浏览器修改数据后，本页直到主动“重新读取”或整页刷新才重新获取；版本冲突仍由服务端返回 409。权限撤销/停用在下一次实际受保护请求被服务端拒绝后处理，不承诺浏览器闲置时立即接收通知。
- 已缓存内容是此前合法获取的数据，展示可继续复用；所有真实写请求仍由后端授权和 CSRF 校验。一般业务 403 不踢用户。后端主动撤销所有服务端会话的策略没有在本次前端修复中实现，后续后端审核需另行确认。
- 无实体手机、全浏览器或慢网压力全量验证。上述延迟是对会话恢复的明确故障注入，不是后端吞吐性能测试。

## 核心文件

`frontend/app/stores/account.ts`、`management.ts`、`portfolio.ts`；`app/composables/useAuthState.ts`、`useAccountProfile.ts`、`useAdminProjectList.ts`、`useProjectEditor.ts`；`app/api/authInterceptor.ts`、`profile.ts`、`projects.ts`、`adminProfile.ts`；`app/middleware/auth.global.ts`；`app/router.options.ts`、`app/utils/scrollPosition.ts`；`app/app.vue`、`assets/css/main.css`、相关登录/注册/首页与管理组件；`nuxt.config.ts`、依赖锁；`app/test/stores.test.ts`、`contentCache.test.ts`、`scrollPosition.test.ts` 及更新的认证/管理测试。

交付：提交此功能分支后停止，等待维护者审核，不合并、不继续后端或新增业务。
