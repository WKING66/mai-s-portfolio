# 阶段 1A：Design Showcase 交付与验收记录

日期：2026-10-10。范围仅为独立视觉样板，等待维护者视觉验收。

## 访问与 Git 边界

- 路径：`/design-showcase`。本机本轮预览：`http://127.0.0.1:3016/design-showcase`。
- 开发模式可直接访问。生产构建默认返回 404；只有测试部署显式设置 `NUXT_PUBLIC_DESIGN_SHOWCASE_ENVIRONMENT=test` 才开放。
- SSR 和客户端路由使用同一个运行配置；不从 URL、Cookie 或浏览器存储启用页面。
- HTTP `X-Robots-Tag` 与页面 robots 均为 `noindex,nofollow`；没有加入正式导航或 sitemap。
- 父分支：`feature/document-comments-flow-docs`。
- 审计与开发基点：`70b6eba02a1cc1018156b84f7b3c28c63e40a4d1`。
- 功能分支：`feature/design-showcase`；在 Codex 独立 worktree 开发。
- 原工作区维护者的 `docs/architecture/document-import-export.md` 未修改、未暂存、未提交。
- 本轮只在功能分支提交，不合并或推送。提交编号以该分支的 `git log -1` 为准。

测试部署开启条件是环境配置，不是新的业务权限或鉴权机制。正式环境不要设置此测试标识。

## 修改清单

| 文件 | 处理与职责 |
| --- | --- |
| `frontend/nuxt.config.ts` | 开启现有 Nuxt UI Prose；增加默认关闭的 Showcase 环境标识及 noindex 响应头 |
| `frontend/app/app.vue` | 仅在样板页隐藏业务 Header 和外层 Toast 容器，避免重复展示和门户主题越界 |
| `frontend/app/pages/design-showcase.vue` | 新增样板入口、环境判断、页面主题状态、Header/Hero/Footer 和组件交互样例 |
| `frontend/app/constants/designShowcase.ts` | 集中维护局部主题值、UI 覆盖、导航、间距、文章与编辑器固定样例 |
| `frontend/app/assets/css/design-showcase.css` | 局部候选主题、排版、表面、焦点和响应式规则 |
| `frontend/app/components/showcase/ShowcaseArticle.vue` | 使用官方 Prose 的阅读视觉样板 |
| `frontend/app/components/showcase/ShowcaseEditor.vue` | 使用官方 UEditor 的 Markdown 编辑交互样板 |
| `frontend/public/images/design-showcase.svg` | 本地静态插图，避免外部资源请求 |
| `frontend/app/test/designShowcase.test.ts` | 新增 14 项浅色/深色文本与语义色对比度测试 |
| `frontend/app/test/designShowcase.integration.mjs` | 真实生产预览与浏览器验收、截图脚本 |
| `docs/testing/design-showcase-20261010.md` | 本文：交付、取舍、测试和验收说明 |
| `docs/testing/screenshots/design-showcase-20261010/*.png` | 六张本轮验收截图 |

没有新增依赖或修改锁文件。原有主题 composable、Cookie、localStorage、首屏恢复脚本、业务编辑器和阅读器均保留；后端与真实博客链路没有改动。

## 主题与组件结构

根节点 `.design-showcase` 接收 `showcaseVariables(theme)` 返回的局部 CSS 变量，复用 Nuxt UI 的 `--ui-bg`、`--ui-text`、`--ui-border`、`--ui-primary` 和语义色变量。Tailwind CSS 4 现有工具类继续消费这些变量，不增加第二套全局主题机制。

| 主 token | 浅色 | 深色 |
| --- | --- | --- |
| Canvas | `#F5F7F6` | `#10191C` |
| Surface | `#FFFFFF` | `#182326` |
| Text | `#1F2D2B` | `#E5EEEB` |
| Primary | `#36776D` | `#82B8AD` |
| Muted text | `#596C69` | `#A2B5B2` |
| Primary hover | `#2B6058` | `#9AC4B8` |

主色梯度限定在局部根节点。中性色复用当前 Nuxt UI 配置，通过局部表面、文字、边框变量形成冷灰层次。语义色按浅色/深色分别提供适合文字和操作的颜色。

字体使用项目现有系统 sans 与 mono 字体，无远程字体。Hero 使用响应式字号；正文基础 16px，阅读区域 17px/1.85，最大宽度 760px；页面容器最大 1200px。间距样本为 4/8/12/16/24/32/48/64px。控件圆角 8px、卡片 12px，默认轻边框无阴影，另展示按需使用的轻阴影。

`UTheme` 注入控件、卡片、编辑器与 Prose 的局部 UI 覆盖。嵌套 `UApp` 将 Modal、Dropdown、Select、Toast 等门户放入 `#design-showcase-portals`；斜杠菜单也附着在此容器内，避免弹层逃逸到全站主题。

样板主题只存在页面 `ref` 中，默认浅色，刷新或离开页面后丢弃。没有读写站点主题状态。代码主题有“跟随页面 / 浅色代码 / 深色代码”三种独立选择。

真实组件包括 Button、Card、Input、Textarea、Select、Form/FormField、Modal、DropdownMenu、Toast、Tabs、Badge、Skeleton、NavigationMenu；展示空表单错误、成功反馈、禁用/加载、悬停、焦点、下拉选择、对话框和移动导航等状态。

## 阅读与编辑器集成情况

阅读样板采用已安装 Nuxt UI 的 `ProseH1`～`ProseH4`、段落、列表、引用、链接、行内代码、表格、图片、分隔线和 `ProsePre`。官方 Prose 没有 H5/H6 组件，使用有语义的原生 H5/H6 配合局部排版。文章样本覆盖中英文、长段落及 Java/Python/TypeScript 的代码长行。

本轮没有安装 Comark，也没有增加临时 Markdown 解析器。固定内容由 Prose 组件展示，并提供同一组内容生成的 Markdown 源码样本；它是视觉样板，尚不是接收任意 Markdown 的 BlogRenderer。代码使用纯文本插值和官方语言标识、复制按钮，复制按钮在桌面端常驻显示；主题选择验证的是代码区域配色，尚未集成 Shiki 语法高亮。

编辑器是实际 `UEditor`，以 `content-type="markdown"` 初始化和导出；`UEditorToolbar` 执行真实撤销/重做、标题、加粗、斜体、行内代码、列表、引用、固定目标链接、代码块和分隔线命令，`UEditorSuggestionMenu` 提供官方斜杠菜单。使用 `ClientOnly` 和延迟加载保证 SSR 不初始化编辑器 DOM。

Markdown 源码模式可编辑并重新进入富文本模式，另可查看当前 Markdown 输出与重置静态内容。仅编辑本地样例，没有博客保存、发布、上传或持久化；没有持久化 Tiptap JSON/HTML。静态样例不使用非标准语法，关闭 underline、mention、image 与 GFM 扩展，避免在这个样板中新增非必要编辑能力。

阅读组件没有 `v-html`。不把后端 HTML 或用户输入 HTML 直接注入阅读页面。源码模式的生产级语法保真、HTML 安全与非标准语法转换保护仍需正式编辑器阶段验证，不能将本样板当成已完成的数据迁移适配器。

## 实际验证

运行环境：Node.js 24.19.0；真实浏览器使用已安装的 Microsoft Edge（Chromium），通过 Playwright 自动操作。浏览器测试启动两个临时生产服务，分别验证默认关闭和测试开启，不启动后端或使用真实账号。

| 检查 | 最终结果 | 覆盖 |
| --- | --- | --- |
| Nuxt 类型检查 | 通过 | `npm run typecheck` |
| Vitest 全套 | 通过，21 个文件 / 245 项 | 包括原有业务与站点主题测试、新增 14 项对比度检查 |
| 最后样式修正后的相关 Vitest | 通过，14 项 | 文本/语义色与主色 hover 对比度至少 4.5:1 |
| 生产构建 | 通过 | `npm run build`，产物合计 8.02 MB，gzip 1.94 MB |
| 浏览器验收 | 通过，4 项 | 页面开放条件、SSR、交互、编辑器、响应式与截图 |
| 桌面/移动布局 | 通过 | 1440×1000、390×844；包含阅读与编辑区域，无页面横向溢出 |
| 局部浅色/深色与隔离 | 通过 | 预置全站深色 Cookie/localStorage，页面从浅色开始；切换后 html 属性、全站背景、Cookie 与存储完全不变；刷新恢复局部浅色 |
| 弹层主题与交互 | 通过 | 门户容器、Modal 配色/焦点陷阱/Escape、下拉键盘导航、选择、Toast、Tabs、表单校验 |
| 代码展示 | 通过 | 三个代码块、独立主题、复制按钮可见、实际剪贴板内容 |
| SSR/Hydration | 通过 | SSR 文章存在、编辑器仅客户端初始化；监听期间没有水合警告或未处理浏览器异常 |
| 键盘与基础可访问性 | 通过 | 跳转主要内容、导航标签、表单标签、Modal 焦点、下拉键盘选择、编辑器 textbox 标识、对比度 |
| UEditor | 通过 | Markdown 初始化、标题/加粗/斜体/列表/引用/代码块/链接、源码编辑往返、斜杠菜单、重置 |
| 业务调用隔离 | 通过 | 浏览器验收监听中 `/api/v1/` 请求为零 |

这不是完整的可访问性认证，也不是所有浏览器或实体手机测试；移动端验证为 Chromium 手机尺寸视口。

验证过程中的失败及处理：首次浏览器启动因默认 Playwright 浏览器未安装失败，改用本机 Edge 后可运行；首次编辑器列表测试使用 DOM `fill` 替换整个富文本树，导致列表按钮禁用，改为从实际源码入口建立普通段落后通过。截图复核又发现官方 `lg:opacity-0` 覆盖复制按钮的普通可见样式，补上局部桌面覆盖和非悬停可见断言，随后重跑。以上失败未记为通过，表格记录的是最终结果。

构建非致命提示：一个压缩后的客户端分包约 531 kB（gzip 约 165 kB），超过默认 500 kB 提示线；另有 Vite/Tailwind 插件耗时提示，以及现有依赖的 Node `DEP0155` exports 尾斜杠弃用提示。UEditor 已按客户端延迟加载，本轮没有为消除提示调整全站分包策略或升级依赖。

复现命令（在 `frontend` 下）：

```powershell
npm run typecheck
npm test
npm run build
# 使用现有 Playwright 安装，不要求给项目新增依赖。
$env:PLAYWRIGHT_MODULE_PATH = '<已安装 Playwright 模块目录>'
$env:BROWSER_EXECUTABLE = '<本机 Chromium/Edge 浏览器可执行文件>'
node --test app/test/designShowcase.integration.mjs
# 测试环境预览；正式环境保持环境标识为空。
$env:NUXT_PUBLIC_DESIGN_SHOWCASE_ENVIRONMENT = 'test'
$env:NITRO_HOST = '127.0.0.1'
$env:NITRO_PORT = '3016'
node .output/server/index.mjs
```

## 验收截图

以下均来自最终生产构建，已经逐张查看。桌面截图宽 1440px，移动截图宽 390px；阅读和编辑截图为独立区域，截图时隐藏粘性页头，避免遮挡正文。

- [浅色桌面](screenshots/design-showcase-20261010/light-desktop.png)
- [深色桌面](screenshots/design-showcase-20261010/dark-desktop.png)
- [浅色移动](screenshots/design-showcase-20261010/light-mobile.png)
- [深色移动](screenshots/design-showcase-20261010/dark-mobile.png)
- [Markdown 阅读区域（深色）](screenshots/design-showcase-20261010/markdown-reading.png)
- [富文本编辑区域（深色）](screenshots/design-showcase-20261010/richtext-editor.png)

自动化原始输出位置为 worktree 的 `.local-backups/design-showcase/`；归档截图随本轮代码提交。

## 设计取舍、遗留问题与后续建议

采用安静的青绿/冷灰、系统字体、轻边框和固定阅读宽度，避免玻璃、流光和倾斜装饰。提供真实组件交互，所有业务样例仅保存在内存。局部主题不会跟随用户全站主题偏好，便于视觉验收时独立对照。

1. 等待维护者确认色彩、排版、留白、表面与编辑器风格；通过后才能逐页推广这些规范。
2. 阅读器阶段集成 Comark、Nuxt UI Prose 和 Shiki，使公开阅读与管理预览最终复用 BlogRenderer；当前代码主题切换是配色样板，不应当作正式高亮实现。
3. 后续修正博客 API，使数据库和 API 均以 Markdown 为内容格式；公开端返回已发布版本 Markdown，保持原有权限、发布版本、下架和资源访问语义。当前公开 API 仅返回 HTML 是待修正缺陷，不是限制前端渲染能力的架构边界。
4. 正式 UEditor 替换前补充原始 Markdown 语法识别、无法保真的内容保护/源码回退、往返转换与 HTML/链接安全测试，避免非标准语法静默丢失；继续只持久化 Markdown。
5. 上传与 OSS 引用沿用现有接口，在后续阶段单独验证；本轮没有触碰保存、发布、版本或上传逻辑。
6. 正式阅读器/编辑器接入时评估延迟分包与体积，增加真实移动浏览器和更完整的可访问性检查。

本轮到阶段 1A 为止，尚未实施以上后续工作。
