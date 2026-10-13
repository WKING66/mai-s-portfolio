# 阶段 2：Markdown API 与统一阅读器

开发分支 `feature/markdown-api-reader`，父分支 `feature/blog-import-workflow@8f69f3c`，包含导入 Hotfix 的 `4dada02`、`8f69f3c`。工作区仅维护者的 `docs/architecture/document-import-export.md` 原有修改，保持不动。三批独立提交，阶段 2 完成后合并回该非 main 父分支；不推送、不合并 main。

## 第一批：API 内容契约

现状复用：Showcase 已有 Comark 安全解析、Nuxt UI Prose、五套 Shiki 主题、复制、文件名降级及目录组件；真实公开页和管理预览仍是 HTML。此前尚未完成阶段 2。

契约：`GET /api/v1/blogs/by-slug/{slug}` 的 `content` 从 null 改为已发布不可变版本 Markdown，移除 `html` 字段。OWNER 的 GET/create/save/import/publish 返回当前草稿 Markdown，同样不再返回 HTML。`version` 的管理 CAS / 公开内容版本语义、资源列表、发布指针、下架、下载权限不变。列表仍只返回卡片，不泄露草稿。Markdown 作为公开阅读数据，并不绕过正式导出接口的登录、OWNER 和 allowDownload 策略。

保真根因：DocumentResourceResolver 原先用 CommonMark 的 MarkdownRenderer 重序列化整篇正文。现在 `MarkdownDestinations` 使用 AST SourceSpan，只序列化目的地实际改变的图片/链接节点，再替换对应源码范围，保留其他文本的原始字节序列、换行、表格、任务列表、删除线、代码语言/文件名和未知扩展语法；稳定资源引用再次保存不改正文。实际变化的资源节点可规范化写法（引用式图片变为含稳定引用的内联图片），其余定义和正文不删除。代码块与行内代码不参与资源遍历。导出也复用该精准改写，临时 URL/ZIP 路径不回写数据库。

验证：DocumentPipelineTest 16 项，启动/安全边界 7 项、OpenAPI 4 项、架构 4 项共 31 项通过。另在现有 PostgreSQL 5432 服务创建独立 `blog_document_test_stage2_20261011_1528` 库，运行真实 Flyway/MyBatis/事务生命周期 7 项通过（外部存储模拟）；新增公开 Markdown 与草稿隔离断言。测试只清空该专用库，不使用开发库。Nuxt 类型检查通过。首次 Maven 使用 PATH 中旧 JDK 报不支持 release 21，改用项目现有 `D:/jdk21` 后通过。前端 `html` 类型暂作为旧调用方迁移占位，第三批彻底移除，不作为 API 兼容字段。



## 第二批：统一安全阅读器

复用 Showcase 的 Comark、Prose 和 Shiki 组件，抽出 BlogRenderer/BlogToc，增加受资源投影控制的 BlogImage/BlogLink。服务端与 Worker 使用同一解析函数；SSR AST 通过 Nuxt payload 水合，客户端路由与预览的重解析放入单个可复用 Worker。资源 URL 刷新只更新图片/链接投影，不重新解析正文。代码高亮限制每块字符/节点与整篇总预算，超出预算保留完整纯文本与五套代码主题、复制功能，不制造成千上万个 token 节点。原始 HTML、任意属性、危险协议不进入 DOM；任务列表及表格对齐保留。

验证：类型检查、Vitest 28 文件 272 项、生产构建通过。真实 Edge 样板浏览器回归 11 项通过、1 项跳过（当前验收环境开放 Showcase，生产关闭测试不适用）；新增 Worker/任务列表/表格/XSS/主题/复制检查通过。最初样板回归失败两项（目录定位与移动预览），处理正文隔离范围和标题滚动间距，并修复异步 setup 尚未注册 watch 时漏掉输入变化的问题，复测通过。表格对齐测试修正为接受 Nuxt UI 的逻辑方向 text-align:end，而不是误判为仅支持 right。

## 第三批：真实页面接入与最终验证

公开详情 `[slug].vue` 与 BlogEditor 的“已保存版本预览”共享 BlogRenderer。编辑输入仍是现有 Markdown 文本编辑流程，预览只读已提交 snapshot；资源地址刷新不覆盖未保存输入。公开正文最大宽度沿用 760px token，桌面旁侧目录、移动顶部可滚动目录，复用现有 Unicode 锚点、章节定位、平滑滚动及 reduced-motion。删除无引用的 HTML 渲染方法、articleHtml/deferArticleImages 与相应专用测试、document-content CSS；Showcase 的 H5/H6 改为复用阅读器样式。没有变更后台生命周期、Slug、导入、上传、权限或数据库结构。

SSR 回归根因与修复：在异步业务页面内，Vue useId 实际出现服务端 `v-3-0` 与客户端 `v-0-0` 差异。Nuxt 因正文缓存键不匹配，在水合时清除了正文；使用稳定的 public/manage + documentVersionId 键后，直接加载四个主题/视口组合均无水合错误，且不在客户端重复解析 SSR AST。客户端导航和预览重解析才启用 Worker。真正的 C/C++ 算法文章按需加载两种官方 Shiki 语法，保持单实例、相同预算和主题。图片使用 ProseImg 的原生 lazy/async，关闭每图片的缩放监听；实际 OSS 图片成功加载并截图。

### 最终测试结果

| 检查 | 实际结果 |
|---|---|
| Nuxt 类型检查 | 通过 |
| Vitest | 27 文件、271 项通过；旧 HTML 辅助测试随旧实现删除，新增 C/C++ 验证 |
| 前端生产构建 | 通过，server 输出 11.7MB；既有 DEP0155 依赖弃用警告仍存在 |
| 后端 Maven package | 通过；DocumentPipeline 16 + 启动/安全 7 + OpenAPI 4 + 架构 4，共 31 项通过 |
| PostgreSQL 生命周期 | 独立测试库 8 项通过；真实 Flyway/MyBatis/事务、外部存储与身份模拟 |
| Playwright/真实 Edge | Showcase + Worker + 生命周期合计 14 项通过、1 项跳过；跳过的仅是当前测试环境中生产关闭 Showcase 检查 |
| 导入 Hotfix | 独立浏览器脚本通过：240/280/320/390 容器、模式/旧异步结果隔离、互斥、附件、失败保留、Slug 重载/发布/锁定 |
| 真实公开阅读 | 9333 新 Markdown 契约、3000 SSR/Hydration、1440/390 × light/dark、目录与无横向溢出通过 |
| 阅读器集成 | 任务列表、表格对齐、图片与附件、未授权 asset 引用降级、原始 HTML/脚本 URL 防护、复制、代码主题/Portal、签名 URL 刷新且代码主题保留，通过 |
| 压力检查 | 5 类样例 × 4 视口/主题，共 20 组通过：空闲恢复、图片延迟加载、首尾可读、前进/返回、缓存复用、无页面错误/水合异常 |

持久化测试补充断言：带 CRLF 的表格、删除线、任务列表、代码语言/文件名、原始 HTML 和代码内资源文本保存及发布后完全一致；JSON 不再含 html。非 OWNER 管理和禁止下载仍被拒绝，公开读取 Markdown 不要求 OWNER。草稿删除图片与附件后，公开版本仍保留两条原资源；下架后 publicArticle 返回 NOT_FOUND，并不调用签名服务。真实维护者开发库没有执行保存/发布/下架测试写入，相关浏览器操作使用浏览器内 API 投影 fixture；持久化写入只在上述专用测试库进行。原 3000/9333 服务按现有配置更新，无额外应用服务。没有执行会额外起服务的旧 siteVisual.integration 脚本，其样例契约已同步更新；不把它标记为通过。

失败记录：第一批首次 Maven 的旧 JDK 问题已记录；第二批两项浏览器回归已修复。第三批首次真实 SSR 检查失败，稳定版本缓存键修复后通过。测试环境变量初次未识别 .env 等号前的空格，独立 DB 启动失败，修正读取后重跑通过。复制测试按 Windows 剪贴板的 CRLF 换行规范化比较后通过，正文与复制源未做有损修改。

### 实测性能（最终版本）

真实“数据结构与算法”：56,183 字符，38 个资源/延迟图片，10 个代码块，正文 3,524 个元素。真实 Edge 从博客列表首次点击，使用 9333 实际 API，未拦截正文或图片。页面 JS 堆不包含 Worker；一次测量不能代表所有硬件。

| 视口 | 首次显示正文 | 最长主线程任务 | 页面 JS 堆 | 后续 1.5 秒内任务总耗时 |
|---|---:|---:|---:|---:|
| 1440 | 2,272ms | 169ms | 26.13MB | 94ms |
| 390 | 2,218ms | 143ms | 28.27MB | 87ms |

C/C++ 语法首次在 Worker 加载使冷进入增加等待，但没有占用主线程连续解析/分词。五类合成样例最终数据：短文 206–232ms，1000 段正文 119–130ms，60 个超长代码块 577–654ms，120 图片 91–102ms，3000 单元格表格 302–387ms。图片首屏仅加载 15–17 张；后续空闲段任务耗时不超过 5ms。极端代码样例最大单次主线程任务 **543ms**，仍有可感知的首渲染停顿，主要阅读场景未出现持续忙转/电脑锁死；不承诺任意规模正文零长任务。高亮预算外仅降级着色，不截断代码或正文。

原始指标与 CPU profile：`docs/testing/performance/markdown-reader-stage2-20261011/markdown-reader-first-click.json`、`markdown-reader.json`、两个 `.cpuprofile`。此次不将不同正文契约和缓存状态的旧 HTML 指标当作严格同条件基线。

### 截图与复现

验收地址：`http://127.0.0.1:3000/blog/blog-import-ui-qa-20261009-17%3A14`；后端 `http://127.0.0.1:9333`。`/design-showcase` 保持当前显式测试环境可用，正式默认仍关闭。管理预览从现有管理页面展开“已保存版本预览”。

截图根目录：`docs/testing/screenshots/markdown-reader-stage2-20261011/`。

- `business/article-{light,dark}-{1440,390}.png`：真实文章 SSR 及响应式；`list-*.png`：真实列表。
- `business/real-resource-image.png`：真实 OSS 图片。
- `business/reader-dracula-portal.png`、`reader-github-light-portal.png`：代码块及展开选择器。
- `business/management-saved-preview.png`：真实管理组件/隔离 API fixture 的已保存草稿预览。
- `business/acceptance-results.json`：正文数量、资源地址刷新及无异常记录。
- `showcase/`：浅深桌面移动、阅读、UEditor、控件和焦点/Modal/Toast 回归。
- `import-hotfix/`：窄容器导入、Slug 编辑及管理流程回归。

使用已有集成脚本时设置 `EXTERNAL_ACCEPTANCE_URL=http://127.0.0.1:3000`，浏览器模块路径和 Edge 路径使用本机环境。业务阅读脚本 `frontend/app/test/blogReader.acceptance.mjs` 固定连接 3000/9333；性能脚本 `articleFirstClick.integration.mjs`、`articlePerformance.integration.mjs` 同样连接现有端口，不开启模拟应用服务器。

### 兼容性与保留取舍

| 内容 | 后端保存/API | Comark 阅读 | 编辑器状态 |
|---|---|---|---|
| 标题、段落、列表、引用、链接 | 未改目的地的源码原样保存 | 标准 Prose | 正式仍使用原源码编辑流程 |
| 表格/对齐、删除线、任务列表 | 不再整篇 CommonMark 重序列化 | 保留对齐与完成状态，任务复选框只读 | Showcase 往返差异检查保留源码；不宣称所有 Tiptap 扩展等价 |
| 代码语言、文件名、长行 | 保留原文 | 官方 Shiki，五主题、复制、可用降级；额外 C/C++ 按需加载 | 本轮不替换正式编辑器 |
| asset://、resource:// | 保存时绑定为稳定引用 | 只查当前版本授权投影，不任意查资产 API | 输入与 snapshot 分离，临时 URL 不回写 |
| HTML/未知扩展/组件语法 | 保留源码 | 原始 HTML 和未启用语法呈现文本；不执行作者组件/任意属性 | 不静默删除存储内容；后续富文本不支持时需保留源码 |

遗留风险：已经由旧保存流程改写丢失的历史格式无法从现有数据自动恢复，需要原文对比；实际改变的资源节点仍可规范化写法，但其余源码不重序列化。OSS **已经签发** 的地址仍按现有 TTL 失效，下架阻止新公开投影和新签名，不宣称可以立即撤销旧直链。正式下载继续有原权限检查，但公开 Markdown 自身按本轮明确契约可读取。任意体量文章的 SSR/DOM 创建仍有成本，极端代码样例的单次停顿如上；未新增虚拟化阅读/第二套控件。UEditor 的正式替换与未知方言完整往返属于阶段 3，必须先通过本阶段维护者验收。

### Git 交付

第一批 `ac98628`：API Markdown 与源码保真；第二批 `4830517`：安全统一阅读器、Worker 与有界高亮。第三批记录在功能分支最终提交中，包含真实页面接入与本报告/截图/测试。完成提交后按维护者要求 fast-forward 合并回 `feature/blog-import-workflow`，不合并 main，不推送。

维护者的 `docs/architecture/document-import-export.md` 与前后端 .env SHA256 与开工时相同，均不暂存、不提交。完整文件清单见相邻 `markdown-reader-stage2-files-20261011.txt`。
