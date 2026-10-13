# 前端统一鉴权、Auth 组件与代码审核记录

日期：2026-10-05。功能分支：`feature/frontend-auth-interceptors`；父分支：`feature/project-navigation-login-ui`（`63950f43d41651dcc2d80563e49a5bcf73678353`）。尚未获得合并授权。

## 本轮需求与实现边界

将组件、项目请求中的共通鉴权抽至统一拦截器；使用 Auth 控制受限元素/子组件展示；补充必要注释；完成前端代码审核和真实浏览器联调。不新增业务接口，不改 RSA、Cookie、Sa-Token、CSRF 或后端授权协议。

- 全局路由拦截器读取页面 `auth` 元数据，统一查询服务端会话，再决定登录回跳、无权限页或放行。
- 统一请求拦截器在派发前检查访问策略、写请求注入 CSRF，集中处理 401/403；错误登录 POST 不走管理请求拦截，避免重定向循环。
- `Auth` 使用同一权限规则，未确认会话不挂载受限内容；不会自行请求数据、导航或只通过 CSS 隐藏内容。
- 导航、管理内容、登录页站长入口使用 Auth；编辑器失权时卸载并销毁本地表单、标签和版本快照。
- 会话恢复采用 single-flight；登录、注销或权限变化使旧请求失效；较早的管理响应不得回填新会话。
- 同一账号仅刷新 CSRF 不改变会话世代，不误丢正常响应、不卸载仍有权限的编辑器。
- 保留 loaded/pending/dirty、版本号、发布条件等业务判断；它们不是应移入鉴权层的权限规则。
- 注释解释 SSR 隔离、Nuxt 上下文绑定、世代保护、禁止写请求重放、失权卸载等非显然边界。

## 验收环境

- Nuxt 生产预览：`http://127.0.0.1:3004`；真实 Spring Boot 验收服务：`http://127.0.0.1:19334`。
- 沿用独立 `portfolio_dev.project_acceptance_20261005` schema、隔离 Redis 命名空间和既有 `project_owner/project_normal` 账号。没有修改正式账号、public schema、OSS 或 Docker。
- 密码只从被忽略的本机验收环境文件读取；以下不记录密码、公钥密文、Cookie 或 CSRF 值。
- 浏览器为本轮独立会话 `frontend-auth-20261005`，未操作用户已有标签页。
- 最终构建再次验证了站长登录、编辑器读取、携带站长 Cookie 的管理 SSR 以及会话失效 401 链路。

## 真实浏览器操作与结果

### 1. 匿名直接输入管理 URL

1. 打开 `/admin/projects/new`。
2. 自动 `GET /api/v1/auth/session` → 200，匿名状态。
3. 跳到 `/login?returnTo=/admin/projects/new`，出现用户名、密码及“登录”按钮。
4. DOM 管理输入数为 0，管理导航数为 0；没有请求 MANAGE 列表、技术标签或项目详情。
5. 登录页挂载后的会话恢复是独立页面查询；这次路由跳转共出现两次会话 GET，不是管理数据请求或自动重放登录。

### 2. 错误登录与站长登录回跳

1. 登录页用户名输入 `project_owner`，密码输入明确错误的非真实测试值，点击“登录”。
2. `POST /api/v1/auth/session` → 401；页面提示“用户名或密码错误”，仍停留登录页。
3. 密码框清空，登录按钮恢复可用；没有循环跳转或自动重试 POST。
4. 输入隔离站长密码（脱敏），再次点击“登录”。POST → 200；随后会话 GET → 200，确认 OWNER。
5. 回跳 `/admin/projects/new`，`GET /api/v1/admin/tags?kind=TECH` → 200；新增表单及“保存项目”按钮可用。本轮未点击新建保存，没有新增项目。

### 3. 编辑器正常读取与管理列表

1. 打开 `/admin/projects/1`，守卫会话 GET → 200。
2. 标签 GET → 200；`GET /api/v1/admin/projects/1` → 200。
3. 页面显示“验收项目 A · AI Agent”及完整表单。
4. 点击“项目管理”，进入 `/admin/projects`；`GET /api/v1/projects?view=MANAGE&page=1&size=20` → 200。
5. DOM 14 条管理项目，“新增项目”、编辑和发布/下架入口存在。状态筛选选择“草稿”，查询追加 `status=DRAFT` → 200，页面 10 条草稿。

### 4. CSRF_INVALID 不注销、不丢输入、不重放

1. 项目 1 编辑页将成果输入为 `Auth interceptor acceptance: keep this unsaved input`。
2. 仅在验收浏览器中临时使下一次 PATCH 的 CSRF 头无效，点击“保存项目”。这不是修改产品代码或伪造服务端响应。
3. 真实 `PATCH /api/v1/admin/projects/1` → 403/CSRF_INVALID；拦截器会话 GET → 200。
4. 仍在 `/admin/projects/1`，管理导航和编辑器仍存在，输入原样保留；提示“安全令牌已更新，请重新提交本次操作。”。
5. 实际只有一次失败 PATCH，没有自动重放。恢复原浏览器 fetch 后，由用户式点击“保存项目”显式重试 → 200。
6. 页面提示“项目已保存”，项目仍已发布，版本由 5 升为 6，成果保存为上述验收文本。其他项目没有变更。

### 5. 撤销站长角色

1. 仅通过已有隔离夹具，将 `project_owner` type 暂改为普通账号；不改变正式账号。
2. 在已打开的管理列表中将状态筛选切回“全部”。
3. MANAGE 列表 GET → 403/FORBIDDEN；会话 GET → 200，角色不再含 OWNER。
4. Auth 卸载管理内容，跳到 `/forbidden`；DOM 项目条目数为 0，显示无权限页。
5. 夹具恢复 OWNER，直接打开 `/admin/projects/1` → 200，可重新看到原项目资料；不是依赖旧前端状态放行。

### 6. 停用账号

1. 仅将隔离 `project_owner` status 暂改为停用。
2. 已打开的编辑页输入 `Disabled owner must not persist`，点击“保存项目”。
3. PATCH → 403/FORBIDDEN，随后会话 GET 同样 → 403；清理前端角色和权限。
4. 跳到 `/login?returnTo=%2Fadmin%2Fprojects%2F1`，编辑器消失，出现密码输入，管理入口数为 0。
5. 停用期间的成果文本未保存；夹具随后恢复 enabled/OWNER。

### 7. 普通账号、直接请求和直接管理 URL

1. 在登录页输入 `project_normal` 与隔离密码（脱敏），点击“登录” → POST 200，GET 200。
2. 页面显示 `project_normal，你已登录`、返回作品集与“注销账号”，没有“管理项目”。Nuxt 内存中 roles=[]。
3. 浏览器同源手工 `GET /api/v1/projects?view=MANAGE` → 403/FORBIDDEN；不是合法页面按钮操作，明确作为负向请求。
4. 地址栏输入 `/admin/projects` → 守卫查询会话，跳 `/forbidden`；无管理项目条目。
5. 普通账号打开公开首页 → 正常 4 张公开卡片，无管理导航；localStorage 仅有主题配置，没有权限持久化。

### 8. 篡改客户端角色不能越权

1. 在普通账号的公开首页，仅临时把 Nuxt 内存角色改为 `[OWNER]`；不修改服务端账号。
2. 直接 MANAGE 请求仍 → 403/FORBIDDEN。
3. 点击此时临时伪造显示的“管理项目”入口；守卫重新查询真实会话，恢复 roles=[]，跳 `/forbidden`。
4. DOM 管理条目数为 0。前端可被篡改的展示状态不构成服务端授权凭据。

### 9. 普通账号注销与清理

1. 打开 `/login`，点击“注销账号”。
2. `DELETE /api/v1/auth/session` → 200；此操作使用 AUTHENTICATED 策略，不要求 OWNER。
3. 登录表单重新出现；loggedIn=false、roles=[]、csrfToken=null；管理 Nuxt 缓存键为空。

### 10. 注销后旧管理响应不能回填

1. 重新通过登录页登录隔离站长，进入管理列表。
2. 浏览器仅暂缓下一次真实 MANAGE 响应的交付；选择“草稿”，服务器 GET 已返回 200，但页面暂时“正在加载…”。
3. 点击管理导航“注销”，DELETE → 200；进入登录页。
4. 再释放此前挂起的真实管理响应，并恢复原浏览器 fetch。
5. 页面仍在 `/login`：loggedIn=false、roles=[]、CSRF 已清空、管理缓存键为空、项目条目为 0、编辑器不存在。旧成功响应没有恢复管理内容。

### 11. 最终构建的 SSR 与真实 401

1. 切换至最终生产构建，重新通过登录页登录隔离站长，打开项目 1 编辑页，读取接口均 → 200。
2. 浏览器携带站长 Cookie 读取当前管理页原始 HTML：无 `project-title` 表单、无 `acceptance-agent` 管理快照，只有权限确认占位。
3. 通过同源 API 执行 DELETE session → 200，但暂不修改当前页面内存中的 OWNER 状态，模拟服务器会话先失效。
4. 页面成果输入 `Expired session must not persist`，点击“保存项目”。真实 PATCH → 401/UNAUTHENTICATED。
5. 统一拦截器清会话并跳 `/login?returnTo=%2Fadmin%2Fprojects%2F1`；无编辑器，登录输入存在，loggedIn=false、roles=[]、csrfToken=null。该文本未保存。
6. 另实际读取公开首页 SSR：4 张卡片，没有 MANAGE 投影；管理 SSR 不包含编辑器或验收项目私有标识。公开 SSR 行为没有被鉴权重构破坏。

## 测试与构建

- Vitest 42/42 通过；5 个测试文件。集中拦截器新增 20 项，包括普通注销、派发前拒绝、SSR 禁止管理请求、外域拒绝、Headers/CSRF、401/403/409、single-flight、旧会话/旧响应及注销时序。
- 保留 RSA-OAEP 密文 POST、错误提示提取、禁止自动重试写请求及安全 returnTo 等既有测试。
- Nuxt 类型检查成功；最终 Nuxt 生产构建成功；`git diff --check` 通过。
- 构建出现第三方 `@vue/shared` 导出映射弃用警告，未阻断构建；本轮未修改依赖来消除该既有工具链警告。
- 后端代码与依赖没有改动，未重复执行 Maven 全量构建；实际浏览器连接既有真实验收后端完成上述链路。

## 前端代码审核

使用 open-code-review-delegate 获取确定性文件清单和规则，由独立审核 Agent 人工分析；没有调用 OCR 外部 LLM。

- 最新代码变更审核：18 个实现文件全部 reviewed，无 skipped，覆盖率 100%。
- 额外人工核对 3 个测试文件与删除的 owner.ts，前端改动最终 22/22 覆盖。
- 上下文阅读覆盖 frontend/app 全部 37 个源码/样式/测试文件及 Nuxt、package、tsconfig 配置；无改动的图标二进制与锁文件不在审核范围。
- backend/application-dev.yaml 是维护者已有改动，明确排除，不作为前端审核跳过项掩盖。
- 未发现最终代码中可确认的 Critical/High/Medium 缺陷。该结论不是“绝对无漏洞”或维护者批准合并。
- 审核中提出的会话/管理响应竞态、注销与旧 GET 时序、注销提示被页面覆盖均已在本轮处理；对应测试与浏览器证据见上。

### 逐文件覆盖

|类别|文件（相对 frontend/app）|结果|
|---|---|---|
|实现|api/authInterceptor.ts|reviewed：集中错误与并发边界|
|实现|api/permissions.ts|reviewed：路由/请求/Auth 同一策略|
|实现|api/projects.ts|reviewed：setup 绑定，公开固定 PUBLIC|
|实现|api/session.ts|reviewed：保留 RSA 与读取，删除裸注销入口|
|实现|components/Auth.vue|reviewed：确认前不挂载，无渲染副作用|
|实现|components/AdminNavigation.vue|reviewed：统一展示、注销提示与重复点击保护|
|实现|components/ProjectEditor.vue|reviewed：仅保留表单业务限制|
|实现|components/SiteHeader.vue|reviewed：统一管理入口、会话恢复失败提示|
|实现|composables/useAuthState.ts|reviewed：每个 NuxtApp 隔离及上下文绑定|
|实现|constants/auth.ts|reviewed：访问策略/角色/错误码/提示集中|
|实现|constants/projects.ts|reviewed：删除旧鉴权常量，无遗留引用|
|实现|middleware/auth.global.ts|reviewed：统一 metadata 拦截与 SSR 边界|
|实现|types/auth.d.ts|reviewed：PageMeta 类型|
|实现|pages/admin/projects/index.vue|reviewed：失权清空列表，业务与权限分离|
|实现|pages/admin/projects/new.vue|reviewed：父 Auth 管理编辑器生命周期|
|实现|pages/admin/projects/[id].vue|reviewed：保护挂载，保留 ID/key 行为|
|实现|pages/forbidden.vue|reviewed：通用无权限提示|
|实现|pages/login.vue|reviewed：登录错误不进入管理拦截|
|测试|api/authInterceptor.test.ts|reviewed：20 个集中层用例|
|测试|api/permissions.test.ts|reviewed：统一规则与 returnTo|
|测试|api/session.test.ts|reviewed：RSA/会话保留，注销测试迁移|
|删除|middleware/owner.ts|reviewed：所有管理页引用已替换，无机械删除回归|

## 副作用、未验证事项与交付边界

- 唯一项目业务写入为隔离项目 1 的成果更新，版本最终为 6；本轮没有新增、删除或切换其他项目的发布状态。
- 临时角色撤销/停用只作用于隔离验收账号，结束前已恢复 OWNER/启用。认证操作会创建/注销真实隔离会话，最终浏览器为匿名。
- 多标签页会话传播与实时权限推送未实现；服务端角色改变由下次会话查询或受保护请求发现，后端立即拒绝失效权限。
- 未做公网 HTTPS/代理部署、压测、移动设备或全部跨标签页竞态测试。注销 CSRF 403 的通用逻辑经源码/集中层覆盖，但没有单独做普通账号注销 CSRF 的浏览器注入。
- 保留维护者 application-dev.yaml，哈希为 F9B83E771118A1B4436E8754B2F4747445EDE978B702AEC02E67E5CEBD50956F，不纳入提交。useAuthState.ts 的同一鉴权职责因本轮迁移至集中层而更新；没有顺手修改其他模块。
- 本机代理配置单独放在被忽略的 .local-backups，不属于应用代码、认证协议或本轮 Git 提交。
- 提交范围仅上述前端代码、测试及本记录；不提交环境文件、凭据、日志、构建结果或截图。提交后停止，等待维护者审核，不合并父分支或 main。
