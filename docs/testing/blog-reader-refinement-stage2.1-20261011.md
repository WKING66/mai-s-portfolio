# 博客阅读体验阶段 2.1 验证记录

父分支：feature/blog-import-workflow（30e6324）。功能分支：feature/blog-reader-refinement。
维护者 docs/architecture/document-import-export.md 修改保留，不进入本轮提交。

## 第一批：目录与即时定位

- 桌面目录移至正文左侧，独立细滚动条；移动端可展开目录。
- 路由与目录通过单次定位事件衔接，保留 Hash 和浏览器历史，显式 instant 覆盖平滑滚动。
- 标题伪元素透明度闪现：约 300ms 保持，800ms 淡出；减少动态效果时仅短暂提示。
- 目录当前章节与标题提示分离，取消旧动画和过期导航，清理观察器和事件。
- Nuxt 类型检查、生产构建通过；Vitest 27 文件 / 272 项通过。
- 真实 Edge：1440/390px × 浅色/深色通过；跳转约 27,271 / 30,630px，标题位于 Header 下方约 97px，历史与无回弹检查通过。
- 截图和连续帧：docs/testing/screenshots/blog-reader-refinement-20261011/。
- 初次单元测试因新增浏览器事件缺少 mock 失败，补全环境并增加避免二次滚动用例后通过。
- 录制视频所需 ffmpeg 未安装，采用任务允许的真实浏览器连续截图，无额外依赖或服务。

## 第二批：章节和代码折叠

- 一次遍历 Comark 顶层 AST 建立 H1～H6 章节树，正文不经过 HTML 拼接或 DOM 搬移。
- contentCollapsedIds 在阅读器实例内，tocCollapsedIds 在目录实例内；目录显示由二者计算，目录操作不写正文状态。
- 章节 v-show 保留子章节偏好、代码主题和展开状态；隐藏标题 Hash 只展开必要正文祖先。
- 超过 20 行代码默认展示约 14 行；完整源码/高亮节点仍保留，复制完整 45 行已验证。
- 类型检查、生产构建、Vitest 28 文件 / 275 项通过。
- Edge 四组视口/主题通过章节同级隔离、子偏好保留、禁用隐藏正文对应目录箭头、Hash 展开、代码复制和主题保持。
- 章节结构使 content-visibility 估计高度造成真实定位误差，单变量实验确认后移除该 CSS 优化。原生单次 instant 定位 + scroll-margin 不再漂移，标题距顶约 97px。长文性能在第三批重新测量。
- 截图新增 toc-fold、body-fold、code-collapsed、code-expanded。无 API、存储或权限改动。

## 第三批：公式、响应式与整体回归

### 最终交互及状态模型

- 宽屏左侧目录 13rem，正文上限 760px。低于 1024px 收为目录图标，采用 Nuxt UI `USlideover` 左侧面板，避开固定 Header；无遮罩、不锁正文交互、不挤动正文。面板宽度至多 22rem，小屏至少留出 3rem 正文可见区域。支持关闭按钮、Escape、点击外部关闭和焦点恢复。
- 触屏目录及小屏面板不提供独立分支折叠，避免隐藏内容不可达。正文折叠仍单向影响目录；窗口缩回宽屏后恢复原有目录折叠偏好。桌面折叠箭头只在悬停或聚焦出现，预留位置避免位移。触屏正文保留可见的折叠按钮。
- `BlogRenderer` 管理实例内 `contentCollapsedIds`；`BlogToc` 管理 `tocCollapsedIds`；`BlogCodeBlock` 单独管理 `expanded`。AST/原始 Markdown、当前位置、临时标题高亮独立维护，不写回正文、资源 URL 或数据库。
- Hash 导航经路由发出单次定位请求，目录展开必要正文祖先后调用原生 `scrollIntoView({ behavior: 'instant' })`。最新版请求获胜，旧动画取消；浏览器前进/后退与重复点击均重新提示。全局 smooth 不覆盖此行为。
- 高亮使用标题伪元素 + opacity，约 300ms 保持、800ms 渐退，无尺寸变化。减少动态效果时改为 300ms 静态提示。观察器、监听器、动画和异步更新在卸载时清理。
- 超过 20 行的代码默认约 14 行；完整源代码和 Shiki AST 始终保留。折叠不新建 Worker、不重新解析、不请求正文，切换主题保留展开和横向滚动位置，复制完整原文。修复 Shiki 换行与 ProsePre 块级 `.line` 叠加导致的双倍行距，并以实际 DOM 行框数量验证预览为 12～16 行。
- 图片加载前预留高度，避免大量零高度图片触发首屏请求；加载后恢复自然比例。签名 URL 更新不重置预留高度，资源仍从版本资源列表解析。

### 公式渲染及安全边界

- 使用现有 Comark 官方 `math` 插件识别公式，KaTeX 0.17.0 渲染；不增加第二套 Markdown 解析器。`htmlparser2` 原已在依赖树中，本轮列为直接依赖，只把 KaTeX 生成物转成允许的 AST 节点，不解析作者 HTML。
- `trust: false`、`throwOnError: true`、`maxExpand: 200`、`maxSize: 20`；每条公式使用新宏对象，禁止跨公式/SSR 请求宏污染。单条 4000 字符、每篇 128 条/32000 字符预算。失败或超预算安全显示原始 LaTeX，正文继续阅读。
- HTML、属性及作者组件插件继续关闭。公式输出仅保留 KaTeX 排版所需的标签/属性；不允许 script、img、iframe、href、src、事件属性，不使用 `v-html`。危险 URL、HTML 扩展和递归宏已测试。
- 同时提供 KaTeX 视觉排版及 MathML；CSS/字体本地构建。块公式独立横向滚动，不使页面溢出。代码、转义美元符号不被当成公式。
- 独立 fixture：[数学公式 Markdown](fixtures/blog-math-stage2.1.md)，展示入口：[3000 上的公式样板](http://127.0.0.1:3000/design-showcase#math-reading)。没有写入真实博客数据库。

### 方言及保存保真

- 后端现有保存逻辑已直接保存 Markdown，本轮仅扩展生命周期测试，验证 LaTeX 反斜杠、矩阵双反斜杠、多行公式及 CRLF 的字节保真，不修改实现或 API。
- 标准标题、表格、删除线、任务列表、代码语言/文件名、asset/resource 引用沿用已验收链路；代码美元符号和资源字面量不经过公式/资源替换。
- Comark 0.7 官方单美元规则把数字开头内容视作货币/普通文本，如 `$1+2$`；无匹配闭合符也保持普通文本。本轮不改写源文或复制另一套解析规则；数字开头公式可使用 `$$1+2$$`。这是当前方言限制，已明确记录。
- 本轮未替换正式 UEditor。公式的编辑器往返兼容仍须在阶段 3 单独验收，当前展示及保存保真不代表富文本编辑往返已全部通过。

### 实际验证

| 检查 | 结果及范围 |
| --- | --- |
| Nuxt 类型检查 | 通过 |
| Vitest | 29 文件 / 280 项通过 |
| Nuxt 生产构建 | 通过；依赖有既存 DEP0155 exports 提示，无构建错误 |
| 后端相关测试 | 32 项通过：DocumentPipeline 16、生命周期 8、OpenAPI 4、架构 4；使用独立测试数据库，不修改维护者真实数据 |
| 目录/折叠浏览器 | Edge + Playwright，1440/768/390/320 × 浅深色共 8 组；含宽屏→小屏→宽屏、焦点、长距离瞬时定位、历史、必要祖先展开、完整复制、主题/滚动保持、Worker 仅 1 个 |
| 公式浏览器 | 同上 8 组；每组 10 条公式/1065 个样板节点，SSR MathML、客户端一致、危险输入、长公式内部滚动，无水平溢出/页面异常/水合警告 |
| 真实公开阅读 | 实际 56183 字符文章、38 个资源、10 个代码块，桌面/移动 × 浅深色；真实 OSS 图片、签名刷新、表格/任务、复制及主题 Portal 通过 |
| Worker 阅读样板 | 1 项通过，更新/复用、源码、任务/表格、安全、主题及复制 |
| 生命周期浏览器 | 2 项通过，浏览器隔离 fixture 的保存/发布快照/下架、Markdown/ZIP 导入及管理预览；后端真实持久化与权限由上列测试覆盖 |
| 导入 Hotfix 回归 | 240/280/320/390px，模式清理、迟到响应、附件关联、Slug 编辑/保存/重载/发布锁定通过 |
| Showcase 回归 | 11 项通过 / 1 项跳过；跳过仅生产环境页面关闭检查，因为沿用已开启测试展示的 3000 服务，未另起验收服务。UEditor 样板、源码保护、主题隔离、Modal/Toast/Dropdown/Select 等通过 |
| 长文压力 | 20 组：短文、长文、60 代码块、120 图片、3000 表格单元 × 桌面/移动 × 浅深色；缓存重复导航及离开后回收检查通过 |

浏览器均使用现有前端 3000、后端 9333，没有新增应用验收端口。API 浏览器 fixture 使用请求拦截，不搭建额外 mock 服务。实际测试命令分别为 `npm run typecheck`、`npm test`、`npm run build`，以及 `blogReaderRefinement`、`blogMath`、`blogReader.acceptance`、`blogRenderer`、`blogLifecycle`、`designShowcase`、`blogImportWorkflow`、`articlePerformance`、`articleFirstClick` 对应测试入口。

### 最终性能数据与取舍

真实 Edge 有窗口模式从列表点击「数据结构与算法」，独立采样，没有并行构建或其他浏览器测试干扰：

| 视口 | 正文可见 | 最长主线程任务 | 累计布局耗时 | 静止 1500ms 内主线程活动 | 页面 JS 堆 |
| --- | --- | --- | --- | --- | --- |
| 1440px | 3046ms | 714ms | 634ms | 38ms | 39.30MB |
| 390px | 3154ms | 664ms | 596ms | 29ms | 40.34MB |

每组正文 API 读取 1 次；7352 个正文节点、38 张懒加载图片、10 个代码块。没有页面异常或持续忙循环。本阶段加入真实文章已有公式的排版与章节组件，并移除造成锚点漂移的 `content-visibility`，与阶段 2 的 3524 个节点/約 2.2s 可见相比，首载和布局成本**有所增加**，不能宣称性能全面提升。尚有约 0.7s 的首次挂载长任务；后续应专项评估公式/章节挂载优化，并同时保持即时锚点正确，不恢复已有定位缺陷。没有复现此前整机卡死，不等于所有设备都已无首载卡顿。

20 组压力用例最终可见耗时：短文 205～234ms、长文 134～230ms、60 代码块 587～716ms、120 图片 87～97ms、3000 表格单元 307～405ms。静止阶段最大 3ms 主线程活动；120 图首批仅 4/4/7/7 张请求，均可滚动到最后一张。各组无横向溢出或水合错误，重复进入复用缓存，离开后执行 GC 采样并记录堆及节点数。

上述堆值仅页面 JS 堆，不包含 Worker、图片/GPU 或整个操作系统内存；可见耗时包含请求、Worker、渲染及浏览器自动化等待。原始数据在 [真实首载](performance/blog-reader-refinement-20261011/final-first-click.json) 与 [压力结果](performance/blog-reader-refinement-20261011/final.json)，不把它当成跨设备性能保证。

### 浏览器证据及文件

- 全量文件清单：[本阶段清单](blog-reader-refinement-stage2.1-files-20261011.txt)。新增章节、目录树、公式组件/工具及 fixture，修改现有阅读器、代码块、图片、路由、样式和测试；没有删除旧业务编辑器，没有修改博客 API、Document 或权限实现。
- [左侧目录与正文](screenshots/blog-reader-refinement-20261011/layout-light-1440.png)、[深色目录](screenshots/blog-reader-refinement-20261011/layout-dark-1440.png)、[目录独立滚动](screenshots/blog-reader-refinement-20261011/toc-scrolled-light-1440.png)、[目录折叠](screenshots/blog-reader-refinement-20261011/toc-fold-light-1440.png)。
- [正文折叠](screenshots/blog-reader-refinement-20261011/body-fold-light-1440.png)、[代码折叠](screenshots/blog-reader-refinement-20261011/code-collapsed-light-390.png)、[完整代码展开](screenshots/blog-reader-refinement-20261011/code-expanded-light-390.png)。
- [小屏正文](screenshots/blog-reader-refinement-20261011/layout-light-390.png)、[左侧目录面板](screenshots/blog-reader-refinement-20261011/directory-panel-light-768.png)、[深色目录面板](screenshots/blog-reader-refinement-20261011/directory-panel-dark-390.png)。
- [浅色公式](screenshots/blog-reader-refinement-20261011/math/math-light-1440.png)、[深色公式](screenshots/blog-reader-refinement-20261011/math/math-dark-1440.png)、[移动端公式](screenshots/blog-reader-refinement-20261011/math/math-light-390.png)。
- 定位高亮连续帧：同目录 `jump-sequence-{theme}-{width}-1/2/3.png`，与 `jump-flash`、`interaction-results.json` 一起记录真实距离、标题高度、opacity 变化与实际可见代码行数。未生成视频，原因见第一批。
- `business/` 保存真实文章/列表/OSS/代码主题 Portal；`manage/` 保存管理预览；`showcase/` 保存编辑器及交互回归；`import/` 保存导入和 Slug；`worker/` 保存阅读器 Worker 证据。
- 性能原始数据、截图和 CPU profile 位于 `performance/blog-reader-refinement-20261011/`，profile 仅采集调用栈，不记录签名资源 URL。

### 调试中失败与修正

除前两批记录的问题外，公式节点类型与 TS 直接导入路径最初失败，已修正后完成类型、单元和浏览器测试。公式 768px 测试最初错误要求内部溢出，现只在长公式确实超宽的 390/320px 检查内部滚动；所有尺寸仍检查页面无溢出。移动目录最初未等待关闭动画导致旧元素失效，改为按真实弹层退出状态验证。图片压力检查发现零高度加载占位导致请求过多，预留高度后通过。截图复核发现代码双倍行距，修正后加入实际可见行数断言。没有将这些初次失败计作通过。

### Git 与交付边界

- 父分支 `feature/blog-import-workflow`，基点 `30e6324`；独立功能分支 `feature/blog-reader-refinement`。
- 第一批 `29bc792`，第二批 `cce39aa`，第三批提交名称 `feat(blog): render bounded math and validate responsive reading interactions`。
- 完成后按维护者已授权流程快进合并至上述非 main 父分支；不合并 main、不推送远端。维护者文档及两端 `.env` 原内容保持不变，不进入提交。
- 阶段 2.1 完成后等待维护者验收，不进入阶段 3。后续风险优先项为长文初次挂载成本、官方数学方言边界和正式编辑器公式往返兼容。
