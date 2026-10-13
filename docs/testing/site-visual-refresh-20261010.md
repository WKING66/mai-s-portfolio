# 阶段 1B：全站视觉系统推广

## 开发边界与基点

- 父分支：`feature/design-showcase-polish`，验收基点 `414b4fb`。
- 功能分支：`feature/site-visual-refresh`；独立 worktree `F:/dev/codex-data/.codex/worktrees/visual/mai-portfolio`。
- 原工作区 `feature/document-comments-flow-docs` 的维护者修改 `docs/architecture/document-import-export.md` 保留，不进入本轮提交。
- 不修改后端、API、持久化、权限、发布版本、资源流程及正式 Markdown 编辑器，不合并 main。

## 第一批：公共基础

`design-tokens.css` 是运行时主题变量的唯一来源，同时映射 `:root[data-theme]` 与 Showcase 的局部 `data-showcase-theme`。`--site-*` 保留现有调用兼容，`--ui-*` 映射 Nuxt UI 语义变量，Tailwind 通过 `@theme inline` 消费相同值。Showcase 的 TypeScript 调色板仅用于展示元数据；对比度测试读取实际 CSS，并校验展示颜色与 CSS 一致。

保留 Cookie、localStorage、首屏恢复脚本和 `useSiteTheme`；首屏脚本中的两种背景色保持自包含，其值由测试约束。Nuxt UI Color Mode 仍禁用。共享容器宽度 1200px、阅读宽度 760px、导航高度 73px；背景采用低饱和青绿与冷灰，表面采用轻边框和少量阴影。旧 `glass-surface` 类仍被引用，保留类名并迁移为实体表面。

共享 Footer 从首页提取，真实页面只有一份。SurfaceCard 去掉连续光效与倾斜。Header、标题、Nuxt UI 默认组件、焦点、锚点间距、减少动态效果统一。

Modal 横跳源于根元素稳定滚动槽与 Reka 默认滚动条 padding 补偿叠加：只在浏览器支持并启用稳定滚动槽时取消额外 padding，保留官方锁滚动、焦点管理和关闭恢复；不以隐藏横向溢出掩盖问题。Toast 使用唯一 UApp 容器，默认顶部居中。

### 代码控件修复

根因是代码高亮局部颜色与网站控件变量分离，选择框、复制按钮及默认 body Portal 未消费代码主题。抽取 `blog-code.css`，以 `--code-toolbar-bg`、`--code-control-bg/text/border/hover`、`--code-focus-ring` 映射块内 Nuxt UI 变量；颜色由 Shiki 当前背景和前景推导，不为每种主题重复写控件 CSS。官方 USelect Portal 使用块内 DOM 引用，避免 Comark 异步树 SSR / 客户端 ID 不一致导致字符串目标无法挂载。移除外围裁剪，保留代码阅读区内部滚动与圆角。

### 验证记录

第一批类型检查、250 项 Vitest 与生产构建通过。第一次浏览器运行 8/11 通过，3 项因局部 Portal 的 SSR ID 不一致失败；改 DOM 引用后原有 10 项通过，新控件测试需显式允许读取弹出菜单期间被官方 aria-hidden 隐藏的 Trigger。首次对比度测试的 Vite CSS raw 读取失败，改为读取实际 CSS 后保持原 4.5:1 标准，14 项既有对比度检查通过。最终复测结果继续补充。

第一批最终 Edge 浏览器复测 **11/11 通过**，包括 20 组代码主题/网站主题/视口组合的控件与 Portal 文字对比度、五次 Modal 开关几何及焦点恢复、代码尺寸与滚动位置、UEditor、源码往返、目录及局部主题隔离。截图已生成于 `.local-backups/site-visual-controls`。

提交 `8033c6e`：公共 Tokens、代码控件与全站布局基础。

## 第二批：公开与账户页面

首页、Hero、技术栈、项目卡片、博客列表、文章详情、项目栏目、登录、注册、个人中心和无权限页面统一到共享容器、青绿按钮、冷灰实体表面与标题层级。技术栈改为自然换行，移除持续跑马灯及重复图标。倾斜常量与所有调用已确认无引用后删除。没有既有归档路由，不新增业务栏目。

`document-content` 保持接收原 API HTML，仅统一 H1～H6、正文、引用、代码、图片、表格与链接；代码和表格使用内部横向滚动，不隐藏页面溢出。公开阅读仍是 HTML，未做 HTML 反转 Markdown 或正式阅读链路替换。

类型检查与 **250 项 Vitest 通过**，隔离 fixture 的生产构建通过。真实 Edge 生成 10 个页面 × 2 种主题 × 2 个视口及正文截图，共 **44 张**。全站浏览器 6 个用例通过，覆盖主题恢复、页面宽度、公共弹层、焦点、移动导航和权限拒绝；但 after hook 检测到匿名直达管理页转登录时的一条水合警告，整体浏览器套件仍失败，正在对验收基点进行复现定位，不能记为 SSR 验收通过。

测试页面仅通过测试构建的 `pages:extend` 注册，验证真实公共 UApp 的 Modal，不进入生产路由。管理端既有原生确认、博客重载行为保持不变；新测试最初误以为博客重载会弹 Modal，已纠正。旧博客生命周期测试的“Markdown 正文”标签与页面当前“正文”标签不符，最后一批一并更新并执行。

提交 `13ce1c8`：公开与账户页面，以及真实页面浏览器回归基础。

## 第三批：管理页面与交付回归

AdminShell 改为桌面侧边导航、移动端自然换行导航；全部管理插槽仍处在原 OWNER Auth 边界内，鉴权前不挂载业务组件。管理标题、表单分组、列表、操作按钮统一继承公共 Tokens 和 Nuxt UI 默认样式。文章列表与博客编辑外层共用 `admin-content-layout`：宽屏保留主内容与 18rem 辅助区，窄屏自然堆叠。项目、资料编辑和列表只调整最小宽度与外层布局。

截图复查发现 40px 移动账户按钮中的尾部箭头挤出边框，使用官方 `trailingIcon` 样式槽在手机隐藏尾部箭头，保留头像、可访问名称与菜单交互；新增内容边界回归。未修改任何保存、发布、下架、上传、未保存保护、权限、版本或导出逻辑。生命周期测试只修正过时的字段/按钮/复选框标签，保留原业务断言。

阅读宽度新增检查曾失败；将阅读容器样式集中到共享 `.site-reading`，校验计算值 760px 与实际宽度后复测。删除已无引用的 `site-tech-surface`，并将重复的 `site-surface-strong` 合并到 `site-surface`。代码焦点使用不透明的块内前景色，避免默认半透明焦点色受网站主题影响。

## 最终设计规范

| 职责 | 来源 | 使用方式 |
| --- | --- | --- |
| 网站明暗、主色、冷灰与成功/警告/错误 | `design-tokens.css` | `:root[data-theme]` 与局部 Showcase 属性映射相同 Tokens |
| Nuxt UI 语义色 | 同一文件的 `--ui-*` | 背景、文本、边框、控件状态共用基础值 |
| Tailwind 工具类、字体、容器与正文 | `main.css` 的 `@theme inline` 与公共类 | 系统中英文字体，Cascadia/SFMono/Consolas 代码字体；不下载远程字体 |
| Nuxt UI 默认形态 | `app.config.ts` | 控件 8px、卡片与弹窗 12px 圆角，轻边框、有限阴影 |
| 代码配色 | `blog-code.css` 与现有 Shiki 输出 | 同一个局部语义变量组控制按钮、选择框与 DOM Portal |
| Showcase | 原页面与样板组件 | 保留独立主题、UEditor、Markdown 源码与视觉基准 |

## 验证结果与边界

| 检查 | 实际结果 |
| --- | --- |
| Nuxt 类型检查 | 通过 |
| Vitest | 22 个测试文件、250 项通过 |
| 生产构建 | 通过；10.7 MB 总输出，2.65 MB gzip |
| Showcase Playwright | 11/11 通过；共享 Token 精简后再定向复测 3/3 通过 |
| 代码控件 | 两个视口 × 两个网站主题 × 五个代码主题，共 20 组；Trigger、复制按钮、Portal 文字 ≥4.5:1，菜单键盘高亮正常 |
| 全站 Playwright | 六个行为用例通过；after hook 因匿名管理重定向水合警告失败，整套测试退出码仍为 1 |
| 布局与交互 | Modal 五次开关、锁滚动和焦点恢复；Select、Dropdown、Toast、Tabs、移动导航、长代码行、内容边界检查通过 |
| 网站导航锚点 | 真实首页双主题、双视口点击项目导航，目标距顶部约 97px，未被 73px Header 遮挡 |
| 主题恢复 | Cookie 优先、仅 localStorage、系统回退、切换后刷新、局部 Showcase 不污染全站通过 |
| 博客生命周期 | 2/2 通过：保存、显式发布、未保存禁发布、草稿与公开版本分离、下架、Markdown 导入与 md/ZIP 导出 |
| 真实后端只读页面 | 首页、博客列表、已发布详情与登录；双主题和双视口 16 张截图，无页面溢出、浏览器异常或水合警告 |

浏览器为真实 Microsoft Edge，使用普通有头窗口与传统滚动条，放置在屏幕外避免干扰维护者。手机检查为 390px 视口模拟，未执行真实手机、Safari、Firefox 或人工屏幕阅读器验证。管理截图与生命周期操作仅连接隔离 fixture，未使用维护者账号或写入真实后端。真实后端截图仅使用公开 GET。

构建存在依赖 exports 映射弃用提示和大 chunk 提示；未扩展到依赖升级或阅读器/编辑器代码拆分。

### 未解决：既有匿名管理重定向水合警告

严格的全站套件 after hook 捕获 `Hydration completed but contains mismatches.`，地址为 `/login?returnTo=/admin/projects`。使用同一模拟后端与负向用例，对未修改的父分支 `414b4fb` 生产构建验证，得到相同警告与失败退出码。权限拒绝、无 MANAGE 数据请求和无正文挂载的行为断言通过。

结合客户端鉴权守卫与 SSR 管理占位树，推断问题位于首次重定向与水合的时序边界。本轮没有修改该路由/会话行为，也没有放宽测试断言。完整 SSR 验收仍未通过，需单独处理路由恢复问题；不能将本轮全部浏览器测试表述为通过。

### 下一阶段

等待维护者视觉验收。公开博客暂保留原 API HTML 容器；下一阶段再修正已发布 Markdown API 契约并统一 Comark + Nuxt UI Prose + Shiki。正式 UEditor 替换仍留在阶段 3。现有原生确认与博客操作流程继续保留。本轮没有新增 UI 框架、主题状态服务、业务抽象或依赖。

## 访问、截图与复现

- 全站视觉验收：`http://127.0.0.1:3000/`，沿用原工作区的本地前端配置。
- 独立样板：`http://127.0.0.1:3000/design-showcase`，开发环境开放；普通生产默认返回 404。
- 后端验收统一使用现有 `http://127.0.0.1:9333`；前端通过既有代理访问，不启动额外的验收服务。
- 截图及并排索引：[截图索引](screenshots/site-visual-refresh-20261010/README.md)。包括 52 张隔离数据业务页面/正文截图、4 张代码主题展开截图、16 张真实后端公开页面截图，共 72 张。
- `3016`、`3026`、`3036` 等旧预览地址不再作为验收入口。

2026-10-11 按维护者要求，原工作区从 `feature/site-visual-refresh`（`0316d92`）创建 `feature/acceptance-environment`，使用最新视觉代码在 3000 验收。原本的 `feature/document-comments-flow-docs` 分支未合并或修改；维护者未提交的 `docs/architecture/document-import-export.md` 和本地 `.env` 原样保留。

原 IDEA 后端进程在核对期间退出，随后经维护者明确授权恢复 9333。现有配置文件实际位于 `backend/.env`，没有复制或重建配置；将其注入启动进程并沿用 dev 配置。首次只启动 launch 模块时，Maven 缓存中的旧业务产物导致新路径返回 404；停止该次进程，执行当前后端全模块 `mvn -B -DskipTests install`（通过）后恢复正常。后端源码、API 和配置文件均未修改。前端 3000 代理资料接口与后端 9333 的实际响应一致，公开博客列表返回既有 2 篇文章；未执行登录、保存、发布或上传等写入操作。

3000/9333 环境复核：Nuxt 类型检查通过，Vitest 22 文件 / 250 项通过；真实 Edge 在 1440 和 390 视口检查 Showcase 的局部主题隔离及各 5 次 Modal 开关，均无横向溢出或主体位移。真实后端的首页、博客列表、已发布文章、登录页在两种主题和两个视口共 16 次浏览器检查通过，无页面异常或水合警告；登录 SSR 的运行配置携带原本的非空 RSA 公钥。最初探针假定公钥位于 `window.__NUXT__.config`，该断言失败；Nuxt 客户端已移除此全局对象，改为核对真实 SSR 运行配置后通过，没有修改应用配置。原报告中的匿名管理重定向水合问题仍未修复，不能将本次公开页复核等同于完整管理 SSR 验收。前端源码未再改动，生产构建结果沿用本报告此前记录的同一视觉代码构建，本次没有重复执行前端生产构建。

在 frontend 目录使用 Node 24，先安装既有依赖，不改 lockfile：

```text
npm run typecheck
npm run test
npm run build
node --test app/test/designShowcase.integration.mjs
node app/test/siteVisual.integration.mjs
node app/test/blogLifecycle.integration.mjs
```

浏览器脚本允许通过 `PLAYWRIGHT_MODULE_PATH` 和 `BROWSER_EXECUTABLE` 指向本机 Playwright 与 Edge；Showcase 设置 `BROWSER_HEADED=1`。构建型集成脚本直接由 Node 执行，避免 Nuxt 构建子进程继承 node:test 的二进制报告协议。基点复现使用 `SITE_VISUAL_FRONTEND`、`SITE_VISUAL_OUTPUT_DIR` 与 `--test --test-isolation=none --test-name-pattern="anonymous management"`。

不提交 `.local-backups`、node_modules、构建输出、本地配置、凭据和日志；截图与报告作为明确的本轮文档进入第三批提交。不合并或推送任何分支。完整源码文件清单列在文末，PNG 文件清单见截图索引。

## 源码文件清单（相对验收基点）

新增 6 个源码/报告文件，修改 38 个文件；没有删除整份源码文件，删除内容为无引用倾斜常量、重复样式和装饰。另新增本目录截图索引及 72 张 PNG。

| 状态 | 文件 |
| --- | --- |
| 新增 | `docs/testing/site-visual-refresh-20261010.md` |
| 修改 | `frontend/app/app.config.ts` |
| 修改 | `frontend/app/app.vue` |
| 修改 | `frontend/app/assets/css/auth-pages.css` |
| 新增 | `frontend/app/assets/css/blog-code.css` |
| 修改 | `frontend/app/assets/css/design-showcase.css` |
| 新增 | `frontend/app/assets/css/design-tokens.css` |
| 修改 | `frontend/app/assets/css/main.css` |
| 修改 | `frontend/app/components/AccountMenu.vue` |
| 修改 | `frontend/app/components/AdminBlogList.vue` |
| 修改 | `frontend/app/components/AdminEditorSection.vue` |
| 修改 | `frontend/app/components/AdminProjectList.vue` |
| 修改 | `frontend/app/components/AdminShell.vue` |
| 修改 | `frontend/app/components/BlogCards.vue` |
| 修改 | `frontend/app/components/BlogEditor.vue` |
| 修改 | `frontend/app/components/ProfileEditor.vue` |
| 修改 | `frontend/app/components/ProfileHero.vue` |
| 修改 | `frontend/app/components/ProjectCard.vue` |
| 修改 | `frontend/app/components/ProjectEditor.vue` |
| 修改 | `frontend/app/components/SectionHeading.vue` |
| 新增 | `frontend/app/components/SiteFooter.vue` |
| 修改 | `frontend/app/components/SiteHeader.vue` |
| 修改 | `frontend/app/components/SurfaceCard.vue` |
| 修改 | `frontend/app/components/TechStack.vue` |
| 修改 | `frontend/app/components/blog/BlogCodeBlock.vue` |
| 修改 | `frontend/app/composables/useSiteTheme.ts` |
| 修改 | `frontend/app/constants/designShowcase.ts` |
| 修改 | `frontend/app/constants/projects.ts` |
| 修改 | `frontend/app/pages/account.vue` |
| 修改 | `frontend/app/pages/blog/[slug].vue` |
| 修改 | `frontend/app/pages/blog/index.vue` |
| 修改 | `frontend/app/pages/design-showcase.vue` |
| 修改 | `frontend/app/pages/forbidden.vue` |
| 修改 | `frontend/app/pages/index.vue` |
| 修改 | `frontend/app/pages/login.vue` |
| 修改 | `frontend/app/pages/projects.vue` |
| 修改 | `frontend/app/pages/register.vue` |
| 修改 | `frontend/app/test/blogLifecycle.integration.mjs` |
| 修改 | `frontend/app/test/designShowcase.integration.mjs` |
| 修改 | `frontend/app/test/designShowcase.test.ts` |
| 新增 | `frontend/app/test/fixtures/VisualInteractions.vue` |
| 修改 | `frontend/app/test/siteTheme.test.ts` |
| 新增 | `frontend/app/test/siteVisual.integration.mjs` |
| 修改 | `frontend/app/utils/siteTheme.ts` |
