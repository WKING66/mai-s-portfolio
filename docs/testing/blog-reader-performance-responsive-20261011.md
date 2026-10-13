# 阶段 2.1.1 验证记录

父分支：`feature/blog-import-workflow`，基点 `ebd2069`。功能分支：`feature/blog-reader-performance-responsive`。本轮不合并、不推送。维护者的架构文档改动与两份本地环境配置保持原样。只使用前端 3000、后端 9333。四批实现及自动化验收记录见下文，待维护者实际验收；不进入阶段 3。

## P0 根因与取舍

真实公开文章《数据结构与算法笔记》：Markdown 56,183 字符，123 个标题（最大深度 5），117 个结构章节，10 个代码块（最长 5,155 字符），38 张图片、13 个表格、110 个公式展示节点。公式解析尝试受既有 128 次预算保护。没有修改文章、资源或后端。

隔离 Edge 单进程串行验证，1440/390 × 900 CSS 像素，减少动态效果；JS 堆限制 512MB，采样安全闸 384MB，单动作超时 15 秒、进程看门狗 240 秒。先受控短文，再实际文章。Vue 生命周期计数、解析/分词计数、CDP Performance 和浏览器 Trace 同时记录。Vue update 到 updated 的区间是包含子组件的区间，不能相加冒充独立的 Render/Patch CPU 时间。

阶段 2 的 `30e6324` 使用统一阅读器，阶段 2.1 的 `cce39aa` 引入递归章节槽和正文折叠；相关槽由 Comark 在每次组件渲染时重建 VNode。基点 `ebd2069` 实测折叠顶层大章节更新了 117 个章节、10 个代码块、110 个公式及大量 Prose/异步包装组件。实测没有增加 Markdown 解析、章节树重建、Shiki 分词或 KaTeX 解析，也没有重新挂载代码块。不是全文重解析导致。展开还有真实的 Style/Layout 成本；不能只修 JavaScript。

修复沿现有组件路径进行：

- AST 使用 shallowRef/markRaw，结构树仅依赖内容；静态槽用稳定 AST 身份 memo，折叠在 memo 外以 v-show 控制可见性。
- 官方 Prose 显式映射，减少每个正文节点的异步包装。长文 CSR 初次组件准备按帧分批，完成后不再卸载正文。
- SSR 保留完整 HTML；Vue 官方空闲水合与交互水合分散长文水合工作。仅准备期间使用 content-visibility:hidden 跳过待显示章节布局，没有估算高度或长期 content-visibility:auto。无脚本浏览器仍显示完整正文。
- 目录及首屏滚动恢复等到准备完成后才读取标题位置；锚点只定位一次，无大章节高度动画。Observer、监听器、待执行帧在离页时释放。
- 安全 Markdown AST 缓存最多 4 篇、合计 200,000 原文字符；原始资源引用保留，签名 URL 不进入解析缓存。
- 堆快照发现慢图片请求通过 Edge Pending activities → img load/error → Vue provides → 阅读器保留脱离文档的整树。离页取消未完成图片的网络来源属性；没有移动或删除 Vue 节点，没有修改 Markdown 或权限。

`v-if` 仅用于首次 CSR 未准备部分；若每次折叠卸载大章节，会重新挂载代码/公式并丢失块状态，因此折叠保留 v-show。单独 v-show 不能避免动态槽重算，需配合 memo。隐藏 DOM 保留有内存成本，已有高亮与公式预算不变。MathML 隔离、普通换行和 inline-size containment 的单变量实验未消除阻塞，不作为正式修复；公式可访问性保留。

## 性能证据

原始记录在 `performance/blog-reader-fix-20261011/`，Trace 可导入浏览器 Performance 面板；URL 查询参数已剔除。`before-reading.json` 为基点，`after-reading.json` 为优化版。数据是本机样本，不是所有硬件的保证。

| 1440px 操作 | 改前 | 改后 |
|---|---:|---:|
| CSR 全文准备完成 | 2671ms | 2920ms |
| CSR 首载最长 Long Task | 567ms | 101ms |
| 大章节折叠 | 171ms / 117 章节更新 | 61ms / 1 更新 |
| 大章节展开 | 316ms / 最长 269ms | 239ms / 最长 187ms |
| 3 轮折叠展开 | 1293ms / 702 更新 | 715ms / 6 更新 |
| 顶部跳到末尾 | 29ms | 40ms |
| 路由重入解析 | Markdown 1 / KaTeX 128 / 高亮请求 10 | 解析缓存命中 1，解析/公式/分词 0 |

390px 单次折叠 61ms、展开 214ms，6 次操作合计 844ms，最长任务 179ms。正文、代码和公式没有无关重新挂载。所有阶段还记录了 Scripting、Style、Layout、Paint、DOM 和 Heap；Trace scripting 为捕获区间的并集并扣除其中的渲染区间，不与 Vue 包含式区间相加。没有启用项目已关闭的 Vue DevTools，使用 Vue 生命周期计数获得更新证据。

| 1440px Trace 区间 | Scripting | Style | Layout | Paint |
|---|---:|---:|---:|---:|
| 改前折叠 | 113.61ms | 10.45ms | 1.52ms | 0.34ms |
| 改后折叠 | 7.42ms | 10.81ms | 0.56ms | 0.24ms |
| 改前展开 | 138.39ms | 67.75ms | 50.56ms | 2.56ms |
| 改后展开 | 40.26ms | 71.88ms | 58.66ms | 3.28ms |

直接 SSR 刷新也曾出现约 511–573ms 任务，不能把 CSR 改善当作刷新通过。最终直接刷新样本最长任务 139/134ms；无水合错误，所有待显示正文状态均消除。冷服务第一次 SSR 的 TTFB 4704ms，第二个视口 497ms；完整渐进准备（含测试末尾 1 秒等待和 Trace 输出）10542/5410ms。总完成时间没有下降，这是以渐进阅读避免集中阻塞的明确取舍，冷启动服务器解析仍有等待成本。

离页取消慢图片后，390px 连续离页 GC 堆为 25.92 → 32.31 → 32.53MB，最后两轮 DOM 计数均 22,305；未取消时同条件可达 31.81 → 53.03 → 74.25MB。不是拿绝对堆上限代替收敛检查。仅验证了有限轮数，长期内存回归继续由自动化检查约束。

## P0 批次验证

- Vitest：30 文件、283 测试通过。
- Nuxt 生产构建通过；依赖 package exports 尾斜线弃用警告仍存在。
- Edge CSR：真实文章首次阅读、折叠展开、连续定位、代码主题切换、离开返回通过。
- Edge 直接 SSR：1440/390、提前离页和连续重入、水合及完整显示通过。
- 原有阅读验收：真实资源图片、代码复制与主题 Portal、签名投影刷新、危险 HTML/URL 过滤通过（改进后的综合回归仍待运行）。

以下两个批次补齐 PC 布局、移动抽屉及全站矩阵，最终复测记录位于文末。

## PC 阅读布局批次

阅读器提供标题槽，公开页面把已有标题、日期及摘要放入同一个 Grid，目录与标题从同一行开始。没有复制业务 Header、改变 SEO 内容或 API。宽屏目录 280px、间距 32px、正文上限 760px；1200px 以下使用原有 Slideover 降级。移除旧的多层居中与标题固定左偏移。从公共父容器分配空间，没有负 margin。

公开阅读页顶部及返回链接后的间距均 24px。Sticky 偏移 Header + 16px，目录滚动上限扣除首屏已知的容器间距、返回链接、目录标题和底部安全距离；不在滚动中反复测量并修改高度。

Edge 实际文章 1199/1200/1280/1440 × 浅/深色共 8 个布局样本通过：760px 正文不被挤压，280px 目录有效宽度，标题/目录同顶，目录底部在视口内，目录内部滚动不移动页面，章节定位保留约 97px Header 安全距离，折叠仍正常。1440px 目录 x=136.5/y=149，正文 x=448.5，与原先窄目录及正文后置目录相比缩小无意义留白。截图与几何结果在 `screenshots/blog-reader-fix-20261011/layout/`。

## 移动端与全站响应式批次

保留公共容器已提供的手机 16px 安全边距，不逐页复制 padding。目录沿用 Nuxt UI Slideover，宽度为 min(320px, 视口 - 64px)，320px 窄屏降为 256px；顶部避开 73px Header，长目录高度受视口底部 16px 安全区约束，短目录按内容高度展示。恢复轻遮罩及官方焦点陷阱，关闭时沿用 preventScroll 焦点恢复。抽屉 body、nav 与目录列表通过 flex/min-height:0 传递高度约束，列表独立滚动；修复了原有 fit-content 面板裁切长列表、列表自身却没有滚动高度的问题。

长行内分式是本轮复现的页面横向溢出源头：320px 压力样例的页面 scrollWidth 为 1124px，768/1024px 同样溢出。行内公式使用有限宽度的 inline-block 和内部横向滚动，保留 MathML，不裁切公式正文。代码、表格、图片和长 URL 复用已有约束。没有增加 html/body overflow-x:hidden。

实际故障文章 10 个代表宽度 × 浅/深色共 20 样本通过：正文左右至少 16px，页级无水平溢出；阅读中途可以打开目录，内部滚动及焦点陷阱有效，Escape 后焦点与滚动位置恢复。真实 OSS 图片加载会改变固有高度，测试先等待阅读位置附近图片结束加载，再检查弹层的几何稳定性；这不表示所有图片加载都没有布局偏移。

全站矩阵覆盖 320/360/375/390/412/430/639/640/767/768/769/1023/1024/1199/1200/1280/1440 CSS 像素，15 个公开及管理路由、两种主题，合计 510 样本，溢出、主题恢复、浏览器异常及水合错误均为 0。每个样本断言实际 URL，避免将登录重定向当作管理页通过。账户、管理、编辑使用隔离浏览器 fixture，未使用真实管理账号或写库；公开首页、项目、列表使用实际只读 API，压力正文仅存在浏览器。修改前矩阵 `before-results.json` 有 6 个分式溢出样本，修改后 `after-results.json` 保留每页测量及失败元素记录。

阅读交互回归 1440/768/390/320 × 两种主题共 8 样本通过：独立目录折叠、正文单向同步、祖先展开及子章节状态保留、历史前进后退、瞬时定位和标题渐隐、14 行代码预览、完整复制、主题/横向滚动位置保留、代码展开后章节折叠重开不丢状态；每个上下文只有一个解析 Worker。复制检查等待异步剪贴板写入完成，避免立即读取造成空值误判。

本批 Nuxt 类型检查通过，Vitest 30 文件/283 测试通过，生产构建通过。构建到独立文件输出后才切换同一个 3000 服务，避免在验收过程中覆盖服务正在使用的资源哈希；没有增加验收端口。

截图及结果：`screenshots/blog-reader-fix-20261011/mobile/`（真实文章）、`responsive/`（全站矩阵）、`interactions/`（阅读交互）。综合业务与最后一次性能复测见以下记录。

## 最终综合验收

Edge 153.0.4234.48，900px 视口高度，单个隔离浏览器串行。最后一轮在全部布局修改完成、没有并行构建或其他浏览器压力任务时运行。真实文章保持 56,183 字符，资源 38，正文元素 7,586；CDP Nodes 统计还包括文档/脱离文档节点，不能与正文元素数量混为一谈。

| 操作 | 1440px 最终 | 390px 最终 |
|---|---:|---:|
| CSR 完整准备 / 最长任务 | 3055ms / 96ms | 2921ms / 89ms |
| 大章节折叠 / 章节更新 | 58ms / 1 | 70ms / 1 |
| 大章节展开 / 章节更新 / 最长任务 | 224ms / 1 / 178ms | 210ms / 1 / 166ms |
| 三轮折叠展开 / 章节更新 | 706ms / 6 | 720ms / 6 |
| 顶部定位末节 | 40ms | 38ms |
| 连续四次定位 | 139ms | 129ms |
| 代码主题切换 / 最长任务 | 378ms / 124ms | 273ms / 111ms |
| 路由重入 / 最长任务 | 996ms / 52ms | 987ms / 无 Long Task |

最终折叠与展开均只有一个 BlogChapter 更新；BlogRenderer、目录及弹层等必要外壳仍会更新，不能声称根组件完全不更新。无关章节、代码、公式没有更新/挂载，原代码 DOM 身份保持；Markdown、章节树、Shiki、KaTeX 调用均未增加。三轮操作只更新目标章节六次。路由重入有一次解析缓存命中及结构树重建，Markdown/公式/高亮重新处理为 0。

直接 SSR 最长任务为 156/146ms，无水合异常及遗留待显示章节。暖服务 TTFB 339/322ms；完整准备含额外等待、Trace 写出约 5392/5509ms。此前 P0 冷服务 4704ms TTFB 的等待仍是遗留成本，不用暖数据代替冷启动指标。有限三轮离页 GC：1440px 为 26.98 → 48.27 → 48.75MB，390px 为 22.58 → 43.80 → 44.11MB；最后两轮分别增加 0.48/0.32MB，DOM 计数趋稳。与 P0 采样的绝对堆值不同，完整缓存/资源加载状态和采样时机也不同；仅证明本机有限轮数没有按整篇持续累加，不声称零内存保留或无限时长运行已验证。

最终原始数据位于 [final/acceptance-reading.json](performance/blog-reader-fix-20261011/final/acceptance-reading.json)、[final/direct-reading.json](performance/blog-reader-fix-20261011/final/direct-reading.json)。同目录 `*.trace.json.gz` 为标准 gzip 压缩的、已去 URL 查询参数的浏览器 Trace，解压为 JSON 后可导入 Performance 面板。P0 的改前/改后原始 JSON Trace 仍保留供直接比较。未提交包含签名 URL 的诊断堆快照。

| 检查 | 最终结果及范围 |
|---|---|
| Nuxt 类型检查 | 通过 |
| Vitest | 30 文件、283 测试通过 |
| Nuxt 生产构建 | 通过，现有 package exports 尾斜线弃用警告仍存在 |
| 真文章 CSR 性能 | 1440/390 首读、最大章节折叠/展开、连续操作、主题切换、离开返回通过 |
| 真文章 SSR / 回收 | 1440/390 直接刷新、提前离页、三轮重入、无水合错误、回收增量检查通过 |
| PC 布局 | 实际文章 8 样本通过 |
| 真文章响应式 | 10 宽度 × 两种主题共 20 样本通过 |
| 全站矩阵 | 17 宽度 × 15 路由 × 两种主题共 510 样本；额外 36 次移动导航展开/键盘/焦点恢复、34 次账户 Tabs 检查通过 |
| 阅读交互 | 8 样本，独立目录、单向正文折叠、历史、瞬时定位/渐隐、代码状态与复制通过 |
| 公式 | 8 样本，SSR MathML、危险节点过滤、错误回退、宽公式内部滚动通过 |
| 阅读验收 | 4 个实际文章 SSR 样本、真实 OSS 图片；表格、删除线、任务列表、附件、资源投影刷新、危险 HTML/URL、代码复制/Portal 通过 |
| 博客生命周期 | 2 项浏览器 fixture 测试通过：保存、显式发布、草稿/公开版本隔离、下架及 MD/ZIP 导出；不写真实数据库 |
| 导入 Hotfix | 240/280/320/390 实际容器、模式隔离、异步结果、资源关联、Slug 编辑/发布后锁定等回归通过 |
| Showcase | 11 项交互测试通过，1 项跳过（当前 3000 服务显式开放测试入口，未执行默认生产关闭检查）；含 UEditor/源码保真、Modal、Toast、Select、Dropdown、Tabs、焦点、Portal、主题隔离 |
| 首页动画 | 技术栈滚动、卡片移动、不可见暂停及减少动态效果通过；动画采样约 43ms 主线程任务，无 Long Task |

原来需额外隔离服务构建的 `siteVisual.integration.mjs` 本轮未运行；通过固定 3000 的新全站矩阵、现有 Showcase 及业务测试覆盖相关检查，没有另起验收端口。后端没有改动，本轮未重新运行后端测试。真实后台写入/权限生命周期未对维护者数据执行，相关流程使用 fixture；实际后端公开 Markdown 和 OSS 读取已验证。交付前 3000 首页与 9333 博客公开接口均 HTTP 200。

## 修改清单与提交

| 文件（frontend/app 下） | 本轮职责 |
|---|---|
| `components/blog/BlogRenderer.vue` | 稳定不可变正文、官方 Prose、长文分帧准备/延迟水合、标题槽、清理 |
| `components/blog/BlogChapter.vue` | 静态槽 memo；保留内容实例与章节自身可见性状态 |
| `components/blog/BlogImage.vue` | 离页取消未完成原生图片读取，解除整树保留 |
| `components/blog/BlogToc.vue` | 等待正文准备、避免提前全文布局、局部目录弹层及高度约束 |
| `assets/css/blog-reading.css`、`pages/blog/[slug].vue` | PC Grid、公共页面留白、抽屉内部滚动、宽行内公式 |
| `utils/markdown.ts` | 有限安全 AST 缓存，资源原始引用保留 |
| `utils/blogChapters.ts` | 结构与可见性解耦、准备状态及性能计数 |
| `utils/scrollPosition.ts` | 渐进阅读完成后恢复首屏位置 |
| `utils/blogReadingProbe.ts`（新增） | 默认无动作的显式诊断计数，不创建主题/业务服务 |
| `utils/blogMath.ts`、`utils/codeHighlight.ts`、`workers/blogMarkdown.ts` | 显式测试标记下的公式/高亮/Worker 调用计数，普通运行协议保持原样 |
| `test/blogParseCache.test.ts`（新增）、`test/scrollPosition.test.ts` | 缓存边界与等待阅读准备的单测 |
| `test/blogReaderPerformance.integration.mjs`、`test/blogReaderDirectPerformance.integration.mjs`（新增） | 实际故障文章的安全性能、Trace、GC 回归 |
| `test/blogReadingLayout.integration.mjs`、`test/blogArticleResponsive.integration.mjs`、`test/siteResponsive.integration.mjs`（新增） | 实际文章 PC/移动和全站断点、导航、Tabs 回归 |
| `test/blogReaderRefinement.integration.mjs`、`test/blogReader.acceptance.mjs`、`test/blogMath.integration.mjs` | 正文准备/异步交互等待、响应式弹层及独立产物目录 |

未删除业务源码。[完整文件清单](blog-reader-performance-responsive-files-20261011.txt)、[验收结果](performance/blog-reader-fix-20261011/verification.json)、浏览器测量/Trace/截图均在 `docs/testing/`。未改 API、数据库、发布语义、资源权限、导入业务实现及正式编辑器；两份 `.env` 与原有架构文档的 SHA256 与任务开始时相同。工作区后来出现的 Worker 仅格式调整保留，未纳入最后提交。

独立提交：

1. `e47fb78`：P0 根因修复与真实文章性能证据。
2. `8a76759`：PC 目录/标题 Grid 与阅读宽度。
3. `3bd5caa`：移动目录和全站公式溢出、响应式检查。
4. `docs(test): record final Edge performance and acceptance evidence`：本报告及最终综合验收产物，SHA 见交付消息。

## 截图索引与验收入口

仅使用 [前端 3000](http://127.0.0.1:3000/)、[博客列表](http://127.0.0.1:3000/blog) 与 [真实文章](http://127.0.0.1:3000/blog/blog-import-ui-qa-20261009-17%3A14)。后端保持 9333。截图均来自实际 Edge；账户/管理截图使用隔离模拟会话，其他真实文章截图为只读公开内容。

| 场景 | 浅色 / 深色 |
|---|---|
| PC 正文及左侧目录 | [浅色](screenshots/blog-reader-fix-20261011/layout/pc-light.png) / [深色](screenshots/blog-reader-fix-20261011/layout/pc-dark.png) |
| 大章节折叠 | [浅色](screenshots/blog-reader-fix-20261011/layout/collapsed-light.png) / [深色](screenshots/blog-reader-fix-20261011/layout/collapsed-dark.png) |
| 手机实际正文 | [浅色](screenshots/blog-reader-fix-20261011/mobile/reading-light.png) / [深色](screenshots/blog-reader-fix-20261011/mobile/reading-dark.png) |
| 手机目录面板 | [浅色](screenshots/blog-reader-fix-20261011/mobile/drawer-light.png) / [深色](screenshots/blog-reader-fix-20261011/mobile/drawer-dark.png) |
| 手机首页 | [浅色](screenshots/blog-reader-fix-20261011/responsive/home-light-390.png) / [深色](screenshots/blog-reader-fix-20261011/responsive/home-dark-390.png) |
| 手机项目 | [浅色](screenshots/blog-reader-fix-20261011/responsive/projects-light-390.png) / [深色](screenshots/blog-reader-fix-20261011/responsive/projects-dark-390.png) |
| 手机账户 | [浅色](screenshots/blog-reader-fix-20261011/responsive/account-light-390.png) / [深色](screenshots/blog-reader-fix-20261011/responsive/account-dark-390.png) |
| 手机管理 | [浅色](screenshots/blog-reader-fix-20261011/responsive/admin-light-390.png) / [深色](screenshots/blog-reader-fix-20261011/responsive/admin-dark-390.png) |
| 代码主题及展开菜单 | [Dracula](screenshots/blog-reader-fix-20261011/real/reader-dracula-portal.png) / [GitHub Light](screenshots/blog-reader-fix-20261011/real/reader-github-light-portal.png) |

更多登录/编辑/403/404、公式、导入和交互过程截图见同目录各子目录。已目视检查 PC/手机正文、目录、首页、管理及代码菜单的代表截图。

## 遗留边界

- 完整文档准备总耗时未变得更短；本轮目标是分散首次 DOM/水合工作及隔离交互更新。暖 SSR 比冷 SSR 快，冷服务高亮与公式解析仍有等待成本。
- 展开大型章节仍有真实 Style/Layout 成本，最终峰值约 178ms；未进行逐帧高度动画。较弱硬件需维护者继续验收，不能用本机样本保证所有设备。
- 首次准备后以 v-show 保留正文、代码/公式实例，DOM 与内存有明确成本；AST 缓存有上限，有限轮数 GC 结果趋稳，但超长会话、多篇不同文章及更弱设备未穷尽。
- OSS 原文不带可靠图片尺寸时，图片加载可改变段落高度。本轮没有改变 API 或 Markdown；弹层稳定性与图片固有尺寸变化分开验证，未声称消除全部 CLS。
- 数学公式/代码的既有预算与错误源码回退保留，未扩大不可信输入预算。没有重新实现解析器、高亮、编辑器或后台生命周期。

维护者完成本轮性能及响应式验收后，再决定下一阶段。
