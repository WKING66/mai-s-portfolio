# 阶段 1A.1：Design Showcase 交互优化交付记录

日期：2026-10-10。范围仅为现有 Showcase 的反馈修正，等待维护者视觉验收，不进入阶段 1B。

## 访问、开发基点与隔离

- 路径：`/design-showcase`；本轮本机预览：`http://127.0.0.1:3026/design-showcase`。
- 父分支：`feature/design-showcase`；基点：`4fe223beccfa0d25b55b79529806e02bcb03b78b`。
- 功能分支：`feature/design-showcase-polish`。
- 独立 worktree：`F:/dev/codex-data/.codex/worktrees/ds-polish/mai-portfolio`，独立安装前端依赖。
- 原工作区仍在 `feature/document-comments-flow-docs`，维护者的 `docs/architecture/document-import-export.md` 完整保留，未暂存、未提交。
- 开发环境开放；生产默认 404。测试部署必须显式设置 `NUXT_PUBLIC_DESIGN_SHOWCASE_ENVIRONMENT=test`。保留页面及响应头 `noindex,nofollow`，没有加入正式导航或 sitemap。
- 不修改后端、数据库、API、真实博客路由、权限、发布版本、上传或保存流程；不替换业务编辑器。不合并 main、不推送。

本轮功能、测试、截图与文档统一提交到上述功能分支；提交编号见该分支最新提交。阶段 1A 原始记录及截图保留。

## 修改清单

以下路径相对于仓库根目录。

| 文件 | 变更与职责 |
| --- | --- |
| `frontend/app/app.vue` | 仅 Showcase 路由配置滚动锁补偿，解决全局/嵌套 Provider 的共享状态问题 |
| `frontend/app/pages/design-showcase.vue` | 局部顶部居中 Toaster、滚动补偿、文章目录数据与布局 |
| `frontend/app/assets/css/design-showcase.css` | 多主题代码变量、固定高度分栏、独立滚动、表格、小标题、移动布局、reduced-motion |
| `frontend/app/constants/designShowcase.ts` | 扩充真实 Markdown 样例，删除已由块内主题选择替代的旧主题列表 |
| `frontend/app/components/showcase/ShowcaseArticle.vue` | 静态 Markdown 经 Comark + Nuxt UI Prose 展示 |
| `frontend/app/components/showcase/ShowcaseEditor.vue` | 保留 UEditor，新增表格/删除线/选区工具栏、草稿保真与实时预览 |
| `frontend/app/components/showcase/ResizableEditor.vue`（新增） | 复用 Reka Splitter，拖动、键盘、双击重置及移动端视图 |
| `frontend/app/components/showcase/ShowcaseToc.vue`（新增） | 官方 TocLink、章节高亮、原生平滑滚动与历史导航 |
| `frontend/app/components/blog/MarkdownPreview.vue`（新增） | 可复用的 Comark AST + Prose 预览、SSR、更新防抖与异步竞态保护 |
| `frontend/app/components/blog/BlogCodeBlock.vue`（新增） | 官方 ProsePre、块内主题选择、可选文件名、原文复制 |
| `frontend/app/constants/codeThemes.ts`（新增） | 六种选择及文件名/语言/通用标签回退 |
| `frontend/app/utils/codeHighlight.ts`（新增） | 共享 Shiki 实例、七种语法、五套 Token 配色、安全 AST 与有界缓存 |
| `frontend/app/utils/markdown.ts`（新增） | 安全解析、中文唯一锚点、目录、Shiki AST、格式保真比较 |
| `frontend/app/test/markdownPreview.test.ts`（新增） | 五项解析、安全、主题、标签、中文锚点与语义比较测试 |
| `frontend/app/test/designShowcase.integration.mjs` | 保留原四项验收，新增六项真实交互回归与截图 |
| `frontend/nuxt.config.ts` | 注册 `@comark/nuxt`，保持既有 Color Mode 禁用与环境开关 |
| `frontend/package.json`、`frontend/package-lock.json` | 最小依赖声明及固定版本 |
| `docs/testing/design-showcase-polish-20261010.md` | 本交付记录 |
| `docs/testing/screenshots/design-showcase-polish-20261010/*.png` | 本轮真实 Edge 截图 |

新增依赖：`@comark/nuxt`/`comark` 0.7.0、Shiki 4.5.0、`@tiptap/extension-table` 3.31.4。直接使用已有的 Reka UI 2.10.5、VueUse 14.4.0、Tiptap Core 3.31.4，补充直接依赖声明；开发类型补充 `@types/node` 24.19.2。没有加入第二套 UI、Milkdown、Nuxt Content 或另一种 Markdown 解析器。

## 主题与组件交互

继续复用上一阶段的低饱和青绿/冷灰 token、系统 sans/mono 字体、间距与 Nuxt UI 控件。`showcaseVariables(theme)` 只覆盖 `.design-showcase` 内 Nuxt UI 变量，局部 `ref` 不写 Cookie、localStorage、html class 或 `useSiteTheme`。

嵌套 UApp / UTheme / Portal 继续隔离 Modal、菜单、选区工具栏、Toast 的配色。外层 UApp 在 Showcase 中仍关闭 Toaster，局部官方 Toaster 使用 `top-center`，顶部 88px 避开 Header；多条消息使用官方堆叠和关闭交互。

文章目录从 Comark 的官方 toc 插件产生。补齐中文 Unicode 标题锚点并消除重复 ID；目录点击通过 NuxtLink 复用现有路由滚动规则、原生 smooth、历史恢复和 reduced-motion，不修改全站路由。`scroll-margin-top: 6rem` 保留 Header 空间；IntersectionObserver 配合 RAF 节流的滚动位置计算跟踪当前章节。

## Modal 横向抖动：实测根因及最小修复

在完整 Edge 浏览器中复现，使用有传统滚动条的 1440px 视口。无头模式没有复现，因此最终回归使用完整 Edge，窗口放在屏幕外，保留真实滚动条行为。

| 状态 | innerWidth / clientWidth | body padding-right | main 左边界 |
| --- | --- | --- | --- |
| 修改前，关闭 | 1440 / 1425 | 空 | 112.4px |
| 修改前，打开 | 1440 / 1440 | 15px | 104.9px |
| 修改前，再关闭 | 1440 / 1425 | 空 | 112.4px |

既有 `html { scrollbar-gutter: stable }` 已预留滚动条空间，Reka 的 `useBodyScrollLock` 又根据视口宽差给 body 加了 15px 右侧 padding，造成重复补偿，居中主容器开关时左右移动 7.5px。Portal、100vw、焦点管理不是本次位移的根因。

Showcase 在检测到 stable gutter 时配置官方 `scrollBody: { padding: 0, margin: 0 }`；没有 stable gutter 则仍使用默认宽差补偿。外层 UApp 的配置仅在 Showcase 路由启用，嵌套 UApp 同步配置，因为 Reka 的共享锁状态可能首先捕获外层 Provider。滚动锁、`overflow: hidden`、背景事件限制、焦点陷阱、Escape 和关闭恢复全部保留。

最终回归反复打开/关闭五次，对比 main 的位置、宽度及页面滚动；横向误差小于 0.75px、滚动误差小于 1px。同时检查 Modal 内 Tab 焦点、关闭后的触发按钮焦点及 body 样式清理。Dropdown、Select、Toast 与分栏也有几何稳定性断言。

## 富文本双栏与 Markdown 草稿

- 桌面默认编辑/预览约 65/35，编辑范围 45%～75%，预览至少 25%。复用 Reka Splitter；分隔条支持拖动、方向键 3% 微调、双击恢复默认。
- 外框固定桌面 680px、移动 640px，防止组件行内 `height:100%` 覆盖约束而随内容收缩。编辑与预览各自滚动。
- 移动端使用编辑/实时预览 Tabs，以 CSS 切换显示，编辑器与草稿仍保留在 DOM 中。
- `draft` 是唯一 Markdown 草稿，`visualValue` 只保存经过保真检查的编辑器投影。源码输入更新 draft；富文本更新导出 Markdown 并更新同一 draft。没有保存 JSON/HTML，没有写入接口或上传。
- UEditor 在源码/富文本间使用 `v-show`，宽度、主题、移动视图切换不重建实例。浏览器通过同一 DOM 标识、选区格式操作验证拖动后状态保留。
- 预览独立调用 Comark，不读取或克隆 UEditor HTML。150ms 防抖，保留上一份 AST 直到下一份成功，并拒绝过期异步结果；卸载清理定时器。
- 官方固定工具栏、bubble 选区工具栏、斜杠菜单；删除线使用 StarterKit，表格使用匹配版本 TableKit，提供插入、增行、增列、删除表格。
- 源码进入富文本前，对 parse→serialize 的 Markdown 结构进行比较，允许纯空白与等价强调嵌套顺序变化；标题、列表结构、链接目标/标题、代码语言/文件名及表格内容差异会阻止转换，明确提示继续源码编辑并保留完整草稿。

实测通过：真实编辑器更新后的删除线、普通 GFM 表格、粗斜体嵌套、三级列表、Python 代码语言标记、链接 title 及代码文件名，结构与原文等价；删除线操作、插入表格、填写单元格、增加行及选区格式操作会实时反映到预览。图片编辑仍关闭，转换差异时回退源码，不静默删除。

该比较是本阶段的保真保护，覆盖上述实际样例，不是所有 Markdown 方言的无损转换证明。后续正式替换编辑器前仍需用现有文章语料验证脚注、数学、任务列表、特殊嵌套等能力。

## Comark、Prose、Shiki 与性能取舍

`MarkdownPreview` 接收 Markdown 并使用预解析 AST，交给 Comark 的 Markdown 组件及 Nuxt UI 自动注册的 Prose。正文覆盖 H1～H6、列表、引用、表格、图片、链接与分隔线。Nuxt UI 无 H5/H6 专用组件，因此补齐原生小标题的局部排版。

禁用 HTML、组件、属性和 Frontmatter 插件；官方 security 插件限制正文标签与 http/https/mailto 协议，关闭 data 图片。Shiki 使用 `codeToHast`，仅转换生成的安全文本/元素 AST，没有 `v-html`。未知语言保留纯文本；测试覆盖 script、事件属性、Vue 组件及 javascript 链接。

Shiki core 仅导入 Java、TypeScript、JavaScript、Python、JSON、SQL、Bash 七个 grammar 与 GitHub Light/Dark、Nord、Tokyo Night、Dracula 五个主题。复用一个 Highlighter Promise，使用 JavaScript regex engine，避免全语言注册及 WASM。仅遇到代码围栏时动态加载高亮模块；最多缓存 32 个代码/语言结果，草稿其他段落变化不重复高亮不变的代码。

Comark 官方高亮插件目前只处理两套配色，本轮以小型 post 插件将 Shiki 五主题 HAST 转为 Comark AST，以满足块内独立切换。没有新增高亮引擎或 HTML 注入路径。

每个代码块默认跟随局部网站主题，也可单独选择五个命名主题；切换仅修改 `data-code-theme` 和 CSS 变量，保留 Token DOM、原文、尺寸和横向滚动。适度颜色过渡遵守 reduced-motion。文件名可选，否则显示语言，再回退“代码”；长名称截断且保留 title。复制使用原始 code；Windows 剪贴板会把 LF 转为 CRLF，测试只规范这一平台换行差异后逐字比较。

最终构建总产物约 10.7 MB（gzip 2.65 MB），上一阶段为 8.02 MB（gzip 1.94 MB）；该数值含服务端产物，不等于首屏下载量。新增解析与高亮能力增加体积，按需加载并复用缓存；仍有超过 500kB 的客户端分包提示（约 789kB 高亮相关、596kB 编辑器相关），没有为压低提示改动全站分包策略。正式阅读器阶段应基于真实文章与网络条件进一步评估 grammar 按语言加载。

## 验证与失败处理

运行环境：Node.js 24.19.0，Playwright + 本机 Microsoft Edge，1440×1000 桌面与 390×844 移动视口。测试使用生产构建启动两个临时服务，分别验证默认关闭与测试开启。

| 项目 | 最终结果与范围 |
| --- | --- |
| Nuxt 类型检查 | 通过 |
| Vitest | 22 个文件 / 250 项通过，保留原业务和主题测试 |
| Nuxt 生产构建 | 通过；分包大小、插件耗时及现有 DEP0155 提示保留记录 |
| 完整 Edge 交互 | 10 项通过，包含原四项和新增六项回归 |
| Modal / Popup / Toast | 开关位移、滚动、焦点、堆叠、顶部居中及选择菜单交互 |
| 分栏 / 编辑器 | 真实拖动、方向键、双击恢复、实例/草稿/选区保留、独立滚动、移动 Tabs |
| Markdown 保真 | 实际编辑后序列化等价；不支持格式回退源码并保留原文 |
| Shiki | 七种语法、五主题 Token、独立选择、复制、尺寸与横向滚动、无文件名与长文件名 |
| 目录 | smooth、Header 余量、当前章节、快速点击、前进/后退、中文 Hash、reduced-motion |
| 主题隔离 | 预置全站深色，局部浅/深切换不改变 html、Cookie、localStorage 或全站背景 |
| SSR / Hydration | SSR 正文、noindex、生产默认 404；监听中无水合警告或未处理异常 |
| 业务隔离 | 验收中 `/api/v1/` 请求为零 |
| 截图与响应式 | 本轮截图来自完整 Edge，无页面横向溢出 |

最初浏览器回归失败已定位并修复：分栏行内高度导致模式切换收缩；中文标题默认 ID 无法匹配中文 Hash；Windows 剪贴板 CRLF 与原文 LF 的平台差异；同页 Hash 导航保留源码视图，原断言错误地等待隐藏的富文本框。最后补充测试曾错误假定链接 title 和围栏文件名无法往返，实测当前 Tiptap 版本可以保留，改为实际编辑后的保留断言。测试等待实际目标标题，保留真实交互与几何检查。先前失败不计为通过；表格为最终验证结果。

完整可访问性认证、其他浏览器、真实手机及全部 Markdown 方言未执行，不标记通过。本轮包含键盘焦点、焦点陷阱、标签、分隔条语义、菜单键盘操作及现有对比度测试。

复现方式（在 frontend 下，先配置 Node 24 和已安装 Playwright/Edge 路径）：

```powershell
npm run typecheck
npm test
npm run build
$env:BROWSER_EXECUTABLE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
$env:BROWSER_HEADED = '1'
node --test app/test/designShowcase.integration.mjs
```

如 Playwright 不在项目中，通过 `PLAYWRIGHT_MODULE_PATH` 指向已有 Playwright 包；不必为运行验收加入业务依赖。`SHOWCASE_OUTPUT_DIR` 可指定截图输出目录。

## 截图及后续边界

截图目录：`docs/testing/screenshots/design-showcase-polish-20261010/`。包含浅/深桌面与移动整体、文章、编辑器、桌面双栏、调宽后、移动编辑/预览、五种 Shiki 主题、语言标签/通用标签/窄屏长文件名、顶部 Toast 和 Modal。

本轮不连接 OSS，图片编辑维持关闭，链接工具仍链接静态 Nuxt UI 文档；草稿和代码主题仅为页面内状态，不持久化。固定外框高度优先保证滚动与模式切换稳定，不实现全屏写作或自适应编辑高度。

下一步由维护者先验收本 Showcase。正式推广时再设计已发布 Markdown API 返回、权限/版本保留与正式 BlogRenderer 复用，并以真实内容完成安全、资源地址与保真验证。本轮不实施这些后续事项。

参考官方能力：[Nuxt UI Editor](https://ui.nuxt.com/docs/components/editor)、[Comark Nuxt](https://comark.dev/rendering/nuxt)、[Comark Security](https://comark.dev/plugins/built-in/security)、[Shiki 多主题](https://shiki.style/guide/dual-themes)。具体配置已按本机安装版本源码及实际运行验证。
