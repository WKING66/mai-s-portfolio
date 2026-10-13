# 前端候选基线交接 — 2026-10-13

本轮只盘点、保存和验证。没有新增产品功能、修改后端、操作 main、推送或提前开始双 Agent 并行开发。当前候选可供集成负责人评审；不能把前端检查通过等同于全栈生产验收完成。

## A. Git 状态与安全检查点

|项目|确认结果|
|---|---|
|绝对工作区|`F:/code/ai/codex/projects/mai-portfolio`|
|原开发分支|`feature/blog-reader-performance-mobile`|
|已完成产品代码 HEAD|`4544a5d1fd48296c31c53835ed591bbed3934da1`|
|交接/检查点分支|`feature/frontend-baseline-handoff-20261013`，父分支为上述开发分支，保留完整历史|
|当前 HEAD|盘点时为 `4544a5d`；交接文档和只读验证脚本将独立提交在本交接分支，其顶端可作为完整交接检查点|
|main / origin/main 本地引用|`0e89031e831aeb77567d0acb62a20b91ca1ac3bf`；未执行 fetch，不能声称远端实时状态一致|
|分支差异|代码盘点时 main 独有 0、开发分支独有 63 个提交，main 是代码候选的祖先|
|前端未提交/WIP|开始时没有；最新 Mermaid 直接手势代码已提交，后续仅新增本轮验证脚本及交接材料|
|新增未跟踪业务文件|开始时无；忽略的本地配置、依赖和生成产物未加入提交|

63 个提交中，53 个触及 frontend，16 个同时修改 frontend/backend。**开发分支不是纯前端分支，不能把 63 个提交全部视为本 Agent 的前端成果。** 完整提交分类见 [提交清单](frontend-baseline-commits-20261013.tsv)，代码基点相对 main 的 184 个前端文件变化见 [文件清单](frontend-baseline-files-20261013.tsv)。本轮新增只读检查脚本不计入该历史差异数。

代码盘点相对 main 共 2911 个变化文件：frontend 184、backend 220、docs 2504、scripts 1，以及根目录 `.env.example` 和 README。没有发现 node_modules、Nuxt 生成目录或本地备份进入当前跟踪树。大量截图、录屏与性能 trace 是历史验收证据，不属于运行时文件；docs 当前约 606.2 MiB，最大单文件约 47.7 MiB。是否迁移到独立工件存储由集成负责人另行评估，本轮不删除证据、不重写历史。

### 未提交修改：归属待确认，原样保留

1. `backend/mai-portfolio-modules/mai-portfolio-module-asset/mai-portfolio-module-asset-service/src/main/java/dev/amai/portfolio/asset/service/impl/AssetQueryServiceImpl.java`：批量生成地址的优化备注补充，1 行注释变化。
2. `backend/mai-portfolio-modules/mai-portfolio-module-portfolio/mai-portfolio-module-portfolio-service/src/main/java/dev/amai/portfolio/portfolio/controller/ProjectController.java`：参数处理的 FIXME 注释，新增 1 行。
3. 同模块 `service/impl/ProjectServiceImpl.java`：新增 FIXME 注释 1 行。
4. `docs/architecture/diagrams/document/01-overview.mmd`：注释拆行，2 增 1 删。

这些变动不涉及可执行逻辑，但无法仅凭 diff 确認作者是维护者还是其他 Agent；**不是本轮前端提交的内容**。已只读导出补丁和 SHA-256 指纹到：

- `F:/code/ai/codex/projects/mai-portfolio/.local-backups/frontend-baseline-20261013/unknown-worktree.patch`
- 同目录 `unknown-worktree-hashes.json`

这些备份不进入 Git，原文件仍在工作区。它们供归属负责人比对，不能未经审核直接应用到 main。开始与完成前的指纹比对一致。

### 既有 worktree 和旧 WIP

|路径/分支|HEAD|工作区|
|---|---|---|
|当前根目录 / 原开发分支后创建交接分支|4544a5d 代码基点|上述四处未知修改保留|
|`.local-backups/blog-document` detached|0cbd494|干净|
|`.local-backups/convergence` / feature/api-responsibility-convergence|9d2cb32|干净|
|`.local-backups/fix` / feature/backend-maintainability-fixes|9d2cb32|干净|
|`F:/dev/codex-data/.codex/worktrees/design-showcase/mai-portfolio`|4fe223b|干净|
|同目录 worktrees/ds-polish/mai-portfolio|414b4fb|干净|
|同目录 worktrees/visual/mai-portfolio|0316d92|干净|

其他已命名分支的提交均在当前代码候选祖先链内，未发现需要补合并的独立分支提交。以上工作树全部只读检查，没有清理或归档。

另保留 `stash@{0}`：`aa57866fd518a887802d5e114b45fc51b014eb0b`，说明为 `pre-convergence integration: original workspace source WIP 20261008`，涉及 23 个后端文件。其 indexed parent `8a8c670` 也保留。没有 pop/apply/drop；是否已被后续接口收敛工作吸收，须原全栈负责人确认，不能将旧接口代码盲目恢复或当作前端 WIP。

### 提交卫生检查

当前跟踪的环境文件仅为根目录和 frontend 的 `.env.example`。本轮没有提交 `.env`、私钥、令牌、日志或未知修改。源码凭据模式检查命中的是 RSA 标记/动态生成代码与消息常量，没有发现被该规则识别出的内嵌私钥主体；未压缩 JSON 证据中也未匹配常见 OSS/AWS 签名 URL 参数。**这不是全 Git 历史、压缩 trace 和所有秘密类型的完整审计**，集成发布前仍应执行团队标准秘密扫描。

`43a0f33` 标题为“格式优化”，实际涉及后端依赖/存储/请求结构及大量验收证据，并不只是排版。它和其他混合提交需要按实际 diff 审阅，不能只按标题跳过。

## B. 已完成前端成果与实际进度

|成果|代表提交|当前情况|
|---|---|---|
|项目管理/公开项目、资源上传组件、资料管理、SEO|81ee778、ff16138、4827830、651b50f|历史已提交；公开页面和 API 本轮只读验证；Owner 写入未在真实库重跑|
|认证路由/权限入口、账户中心、注册、Pinia 与持久化首屏恢复|22601ea、6099d4a、e614aeb、1b22025、b58fc8a|保留现有 Cookie/CSRF/OWNER 规则；匿名管理跳转和主题恢复通过|
|统一反馈、顶部 Toast|82f022c、4e77220|已提交；单元测试与管理 fixture 覆盖反馈流程|
|Design Showcase 与全站视觉推广|4fe223b、414b4fb、8033c6e、13ce1c8、0316d92、e285324|保留独立主题预览；公开样式/账户外壳/管理布局统一；Showcase 仅开发或 test 环境|
|首页技术栈滚动/卡片动态和状态页|ab530bf、e285324|保留有限动画与 reduced-motion；首页、权限页及 404 本轮检查|
|导入模式隔离、窄面板、Slug 手动编辑|4dada02、8f69f3c|当前导入 fixture 全量通过，不按标题语言区别对待；已发布 slug 锁定沿用后端|
|Markdown API 前端类型和共享 BlogRenderer|ac98628、4830517、30e6324|公开阅读/已保存预览复用 Comark + Nuxt UI Prose + Shiki；Markdown 是正文交换格式|
|目录、正文折叠、长代码折叠、公式|29bc792、cce39aa、ebd2069、e47fb78|章节树与折叠分离，渐进准备；当前真实长文点击/折叠/刷新及移动目录通过|
|长文性能、目录自动跟随、iPad TOC 和响应式|5fe1ea4、e33a427、21082ea、76b0bc1|历史 Performance Trace/设备记录保留；维护者先前确认 iPad 目录恢复、电脑暂未明显卡顿。本轮不声称重新完成整套性能录制或硬件测试|
|Mermaid 安全懒加载、全屏和触摸|ecbf9be、28153f8、6ebc399、4544a5d|最终状态：正文/全屏均直接单指拖动、双指缩放；无缩放按钮/比例/提示/开关；仅源码、复制和全屏入口。尺寸手柄已撤回|

以上是**最终 tip 的累计效果**。不要只挑早期 `fe3919e` / `e615496`，否则会重新带入已被维护者要求撤回的尺寸功能；应包含 6ebc399 与最终 4544a5d。

正式 UEditor 博客替换（阶段 3）尚未实施。UEditor 仅在 Showcase 使用静态 Markdown；真实编辑器保存、上传、发布/下架和未保存保护没有改成 Showcase 逻辑。没有 Agent/RAG、协作编辑或 Document 再抽象化工作混入本轮前端交接。

最新单指/双指手势的新真机验收尚未收到维护者确认。已有浏览器输入验证不能冒充 Mate60 Pro 或 iPad 实机通过；此前旧目录验收也不能替代新手势验收。

### 本轮基线验证

|检查|本轮结果|边界|
|---|---|---|
|Nuxt typecheck|通过|现有类型检查|
|Vitest|31 文件、287 项通过|不等同于浏览器或真实数据库端到端测试|
|Lint|未执行|package.json 无 lint 脚本，未找到项目级 ESLint 配置/依赖；没有用静默跳过命令假装通过，也未临时安装规则|
|生产构建|通过|相同 Nuxt 生产构建，生成目录隔离；既有 DEP0155 导出模式弃用警告保留；3000 服务未被替换|
|依赖声明与锁文件|dependency/devDependency 列表一致|没有重装依赖或在全新机器执行 npm ci|
|真实 API 只读|5 项通过|资料、公开项目、公开博客、匿名会话、真实已发布 Markdown/资源投影；没有 Mock|
|真实页面/路由|37 项通过|1280/390×900、浅深主题：首页、项目、博客、登录、注册、权限、404；真实长文从列表进入、折叠/展开、SSR 刷新；移动 TOC；匿名管理重定向；Dracula/GitHub Light 代码主题选择|
|SSR / Hydration / 溢出|本轮未检测到错误|仅本轮上述场景，不声称所有环境/浏览器都已覆盖|
|管理博客生命周期|2 项浏览器测试通过|使用 3000 真实前端，API 在隔离浏览器内存 fixture；保存/发布、后续草稿隔离、已保存 Markdown 预览、下架、导入及 Markdown/ZIP 导出|
|导入 Hotfix|全量 fixture 检查通过|240/280/320/390px 面板、模式/重选/资源隔离、异步互斥、Slug 错误保留/刷新/发布后锁定；不是实际 PostgreSQL/OSS 导入|
|Mermaid|Edge 1280/390、浅深主题，共 4 组通过|正文与全屏直接单指移动及双指缩放、防误开全屏、原有 SVG ID/主题/源码行为；无新增解析；没有再次生成截图|
|真实认证后写入、资源失效、数据库生命周期|本轮未执行|没有使用真实 Owner 凭据，也未创建/发布/下架真实测试文章；后端负责人须在专用测试库执行|
|新手势/iPad/其他硬件|本轮未执行|历史维护者反馈和当前模拟输入分别记录，不能互相替代|

辅助 Shiki 检查曾把 GitHub Light 的内部值误写成 `github-light`，而实际 `CODE_THEMES` 值为 `light`；根据现有常量修正验证脚本后全量重跑，37 项通过。没有为通过验证修改产品实现或降低断言。

### 复验入口

新增 `frontend/app/test/frontendBaseline.integration.mjs` 是只读真实 API 脚本，不注入响应、不执行真实写入，输出到忽略的 `.local-backups`。需要匿名会话、可用后端和已发布《数据结构与算法》文章。API/页面失败会失败退出。

在 frontend 目录：

```text
npm run typecheck
npm run test
npm run build
node app/test/frontendBaseline.integration.mjs
node --test app/test/blogLifecycle.integration.mjs
node app/test/blogImportWorkflow.integration.mjs
node app/test/blogMermaidViewer.integration.mjs
```

浏览器脚本当前依赖 `PLAYWRIGHT_MODULE_PATH` 指向已有 Playwright 安装，以及 `BROWSER_EXECUTABLE` 指向 Edge。项目 package.json 未声明 Playwright，也没有统一 CI/e2e 入口，不能声称 npm run test 会自动执行这些脚本。`EXTERNAL_ACCEPTANCE_URL=http://127.0.0.1:3000` 应用于生命周期脚本，防止其默认模式另起测试应用服务。设置 `MERMAID_VIEW_CAPTURE=0` 关闭截图/报告；导入输出可用 `BLOG_IMPORT_OUTPUT_DIR` 指向本地备份目录。生产构建本轮使用隔离 buildDir/output，仅避免覆盖现有生成产物，没有改变生产源码。

本轮日志和原始结果位于 `F:/code/ai/codex/projects/mai-portfolio/.local-backups/` 下 `frontend-baseline-*` 和 `frontend-baseline-20261013/`，不进入 Git。交接证据摘要另附，未包含凭据、响应签名资源地址或维护者文章正文。

## C. 前后端依赖与契约

运行关系：Nuxt 3000 → 同源 `/api/v1/**` Nitro 代理 → Spring Boot 9333。默认代理目标为 loopback 9333，可通过 `NUXT_BACKEND_URL` 配置；SSR 和客户端均使用现有真实 API。生产代码未检出接口 Mock/fixture 分支，Showcase 的静态样例是受环境限制的展示，并非业务兜底数据。

|接口/约定|前端用途与集成要求|
|---|---|
|统一 ApiResponse|`{ code, message, data, details }` 与真实 HTTP 错误；不能仅返回 HTTP 200 后隐藏业务失败|
|`GET/POST/DELETE /api/v1/auth/session`|会话/登录/退出；loggedIn、roles、username、csrfToken；固定 RSA 公钥、同源 Cookie、写入 X-CSRF-Token 和 OWNER 校验保留|
|`POST /api/v1/auth/register`|公共注册；与旧独立注册路径不同，须包含对应后端修复|
|`GET/PUT /api/v1/account/profile`、avatar、password|账户中心，头像与密码独立于公开站点资料；后端身份/凭据版本语义必须同步|
|`GET/PATCH /api/v1/profile`|公开资料与站长编辑共享投影入口；旧管理/公开资料分裂 DTO 已收敛|
|`GET /api/v1/projects?view=PUBLIC/MANAGE` 及项目 CRUD/publish/unpublish|明确视图、分页、版本、封面/标签投影、管理 Owner 权限；不能恢复旧两套项目列表协议|
|`GET /api/v1/tags?kind=TECH`|技术标签选项；System 层收敛需由后端负责人确认|
|`GET /api/v1/blogs`、`GET /by-slug/{slug}`、管理 `GET /{id}`|公开已发布版本 Markdown，管理相应草稿 Markdown；documentVersionId、resources、allowDownload 等字段需一致|
|博客 POST/PATCH、imports、publish、unpublish、export|保持 version 互斥、Slug 首次发布后锁定、保存/发布分离、正式导出权限；fixture 通过不证明真实库/OSS 也通过|
|`/api/v1/assets/**` 上传和现有资源访问投影|asset://id、resource://key 是稳定引用；resourceUrl/expiresAt 可刷新，仅展示、不写回 Markdown；READY/INVALID/FETCH_FAILED 与访问控制保留|

相对旧 main，最关键是 `ac98628` 将公开正文 HTML 投影修正为已发布 Markdown 并保留保存原文；前端 `4830517/30e6324` 依赖该契约。不能只合前端阅读器并继续接旧 HTML。其他基础 API 收敛、账号、项目、资源与数据库迁移也包含在 16 个混合提交/后端提交中，需全栈负责人审核。

后端配合事项：

1. 对候选历史中的 API 收敛、Markdown 保真、发布快照、正式导出和公开资源访问规则执行后端测试；本轮未代替该责任。
2. 核对 V2～V5 等新增数据库迁移及当前 schema，不用维护者开发库执行破坏性生命周期测试。
3. 在隔离库/资源环境联调登录、Owner 保存/发布/下架、版本冲突、草稿隔离、OSS 到期刷新、下架后资源失效和上传/导出。
4. 部署注入与后端匹配的 RSA **公钥**、正确站点 URL 与代理目标；私钥只留后端安全配置。手机普通 HTTP 缺 WebCrypto，账户真机验收需受信任 HTTPS，本轮未弱化认证。
5. 确认四处未知修改及旧 stash 是否保留、独立提交或已被吸收。没有请求本前端 Agent 代为修改。

## D. 集成建议与暂停点

推荐把 `4544a5d` 作为**已验证前端代码的候选输入**，交接分支顶端作为带清单和复验脚本的安全检查点。选择完整祖先链或组合提交由原全栈集成负责人决定，先审核 16 个混合提交和必要后端契约，再在非 main 集成分支验证。当前拓扑支持快进，但技术可快进不等于维护者已批准 main 集成。

暂不纳入：四处未知未提交修改、23 文件旧后端 stash、任何本地 .env/密钥/日志/依赖/构建目录、阶段 3 正式 UEditor 替换或尚未实现的 Agent/RAG 工作。不要把旧 Mermaid 尺寸分支作为最终结果，也不要重复合并已在祖先链内的 Showcase/后端收敛分支。

尚需审核的风险：缺少 Lint/统一 e2e CI；真实 Owner/数据库/OSS 写入回归未执行；最新手势真机待验收；长期性能指标不可只凭构建或一次短测判定；约 606 MiB 历史验证工件增大仓库；压缩证据/全部 Git 历史未做完整秘密扫描；旧报告中的已撤回尺寸/旧 HTML 展示描述只能作历史，当前行为以最终代码和本报告为准。

本轮不操作 main、不自动推送、不向旧开发分支追加功能。本交接提交完成后停止后续前端功能开发。只有维护者明确批准新 main 基线且代码实际更新后，再从更新后的 main 创建前端专用 worktree，恢复前端职责；不现在启动双 Agent 或创建该未来 worktree。
