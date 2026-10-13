# 阶段 1B.1：性能与视觉稳定性

修复分支 `feature/site-visual-stabilization`；父分支 `feature/acceptance-environment`（`f283e2a`），包含阶段 1B 的 `0316d92`。维护者未提交的架构文档、本地前后端配置均原样保留。没有合并、推送或修改其他工作区。所有人工验收和本轮浏览器检查使用前端 3000、后端 9333；基线和修复生产构建顺序占用同一个 3000，不启动额外验收端口。

## 第一批：文章首屏与 SSR

维护者指定触发路径：Edge 从博客列表首次点击「数据结构与算法」。文章 HTML 80,778 字符、2,017 个正文节点、10 个代码块、38 张图片。另一篇文章 57,665 字符、998 个节点、12 个代码块。

静态检查没有发现正文重新解析 Markdown、高亮循环、目录 Observer 或持续响应式写入。公开详情仍使用已由后端净化的 HTML 投影；本轮不改变 API、资源 URL、正文和持久化格式。对 `414b4fb`、`0316d92` 的归档源码在本工作区忽略目录构建进行对比，两者均全量插入正文并立即请求全部图片，父分支也存在这个风险。

冷上下文、原生 Edge、GPU 默认开启、单浏览器串行、1440/390 × 900 视口，真实后端，首次点击后采样约 600 ms 并测量后续空闲。CPU profile 显示主要阻塞是全量子树布局，而不是持续执行 JavaScript；首次 38 个图片请求进一步扩大并发资源负担。

|真实文章首次点击|桌面修复前|桌面修复后|移动修复前|移动修复后|
|---|---:|---:|---:|---:|
|点击至正文出现（ms）|554|247|565|282|
|布局耗时（ms）|418|108|430|121|
|最长 Long Task（ms）|422|90|436|104|
|初始图片请求|38|0|38|0|
|正文节点|2017|2017|2017|2017|
|JS Heap（MiB）|6.17|6.32|6.16|6.29|
|随后 1.5 秒任务耗时（ms）|17|56|7|37|

指标来自浏览器 CDP `Performance.getMetrics` 和 Long Tasks，不等于整台电脑或 GPU 的 CPU/内存统计。网络条件会影响图片完成时间。单次采样不能证明所有设备都不存在卡顿；本机未直接复现维护者描述的整机失去响应，不能将其淡化为普通动画问题。现已实测消除主要首屏全量布局负担，未删除正文；仍需维护者在原 Edge 会话重复指定路径确认。

修复采用两个浏览器原生能力：对屏外正文块使用 `content-visibility:auto` 和记忆尺寸，保留完整 DOM、浏览器查找、正文链接及锚点；对 HTML 中的图片在 SSR 输出前添加 `loading="lazy" decoding="async"`，保留签名地址、原尺寸及其他属性。仅修改固定调度属性，不添加 HTML 信任入口；不是 Markdown 解析或 HTML 净化器。Vue computed 按正文变更复用转换结果。

水合问题的根因是服务端输出管理页 Auth 的未确认占位树，客户端初始路由守卫先恢复匿名会话、跳转登录页，再以登录页树水合旧 DOM。只对初始 SSR 水合延后到 `app:mounted` 检查跳转；正常 SPA 导航继续等待权限确认。Auth 仍禁止未确认状态挂载业务内容，后端授权、returnTo 白名单及请求拒绝不变；异步结果也不跳转已经离开的路由。

验证：256 项 Vitest、类型检查、生产构建通过。`0316d92` 的 `/admin/projects` 生产构建实测有水合警告；修复后 `/admin/projects`、`/admin/blogs`、`/account` 均正确进入登录页，0 水合警告，0 MANAGE 请求。短文、1000 段长文、60 个长代码块、120 张图片、3000 个表格单元格 × 两种主题 × 两种视口，共 20 组生产浏览器检查通过；覆盖滚动、返回/前进、反复进入、公开缓存复用、离开后 GC 和空闲任务。样例只在隔离浏览器中拦截公开 GET，不写真实业务数据。

性能样例与真实首次点击脚本分别为 `frontend/app/test/articlePerformance.integration.mjs`、`articleFirstClick.integration.mjs`。在 frontend 使用 Node 24，设置本机 `PLAYWRIGHT_MODULE_PATH` 与 `BROWSER_EXECUTABLE` 后运行：

```text
node app/test/articleFirstClick.integration.mjs after
node app/test/articlePerformance.integration.mjs
```

只有基线采集使用 `--baseline`，它不跳过页面异常、溢出、空闲、缓存检查。脚本不启动应用，浏览器独立进程设定 45/180 秒硬超时并清理进程树，样例按顺序运行。性能数据：[目录](performance/site-visual-stabilization-20261011/)，包含前后 JSON、桌面/移动端截图和可在 DevTools Performance 导入的 CPU profile。CPU profile 不记录网络请求或资源签名。正式测试数据不是后端保存内容。

## 第二批：首页动效

参考 `414b4fb` 的连续图标轨道和卡片交互，恢复四组技术栈的 CSS transform 跑马灯、交替方向及首页卡片 3px 浮起；保留当前 Tokens、边框与排版，不恢复流光、模糊、鼠标坐标更新和全站倾斜。SurfaceCard 的 motion 默认关闭，ProjectCard 透传，仅首页显式启用。两份列表宽度完全一致，重复项对读屏隐藏。

悬停/键盘焦点、手动按钮、隐藏标签页、离开视口均暂停；主题切换保留动画进度。减少动态效果时改为静态换行，触屏不强制卡片 Hover。Visibility 事件及 IntersectionObserver 在卸载时清理。

类型检查与生产构建通过。`homeMotion.integration.mjs` 在 Edge 3000 验证循环宽度、暂停、主题切换、邻卡布局不变和移动端减少动态效果；1.6 秒动画采样仅 51ms 主线程任务、0 Long Task。连续三帧动画时间分别为 1018、1461、1877ms，截图及 Hover、移动静态截图位于 [截图目录](screenshots/site-visual-stabilization-20261011/)。首次测试因 Playwright hover 等待移动轨道稳定而超时，改用固定卡片内的真实鼠标位置后通过，没有关闭动画断言。

## 第三批：异常状态与整体回归

新增轻量 StatusPanel，复用 SurfaceCard、Nuxt UI Button/Icon 与现有 Tokens：静态几何路径、大号状态标识、解释和下一步操作。404 使用 Nuxt `app/error.vue`，保留实际 HTTP 错误码和 noindex；站内安全历史存在时才提供返回入口，首页、博客、项目入口均复用现有路由。403 沿用 `/forbidden` 和原 Toast，不改接口/守卫权限：匿名状态提供登录，已登录但无权限时提供查看账号，明确重新登录不会自动获权。客户端确认后才更新账号相关文案，避免水合树分歧。移动布局垂直堆叠，没有复杂动画或新状态系统。

生命周期测试发现旧博客页的 `throw createError({ statusCode:404 })` 在客户端属于非 fatal 异常，SPA 访问下架文章未进入错误页，而 SSR 正常返回 404。查证本地 Nuxt `nuxt-root.vue` 的错误处理分支后，增加 `fatal:true`，让两种访问方式保持相同的页面语义；没有改变 API 状态、发布控制或权限。修复后的同一测试通过。

|检查|实际结果|
|---|---|
|最终 Nuxt 类型检查、生产构建|通过；依赖 exports 弃用与大于 500kB chunk 警告仍存在|
|Vitest|25 个文件、259 项通过，覆盖权限、请求互斥、缓存、上传资源、Markdown、主题等既有单元逻辑|
|状态页面 Playwright|8 组（404/403 × 浅/深 × 桌面/移动）通过；真实缺失路由及缺失文章 SSR 404、站内历史返回、键盘焦点通过|
|登录/权限回归|匿名 `/admin/projects`、`/admin/blogs`、`/account` 与指定登录 returnTo 页面无水合警告、0 MANAGE 请求；隔离 MEMBER 会话正确跳转 forbidden、不提供误导的重新登录入口|
|既有 Showcase 回归|11 项通过、1 项跳过；涵盖真实 UEditor、源码往返、Shiki 主题/Portal 对比度、主题隔离、Modal 五次开关无位移/焦点恢复、居中 Toast、Dropdown/Select、移动编辑和目录|
|博客生命周期回归|2 项通过：保存、发布、修改草稿不改变已发布内容、下架、Markdown 导入、MD/ZIP 导出；CSRF 与版本断言保留|
|最终性能复测|20 组通过（`final-samples.json`），新增长文末段可见、屏外最后一张图片进入视口后完整加载检查；往返和四次重复进入缓存断言保留|
|首页动效|第二批浏览器检查通过，三张实际连续帧与负载数据可查看|

本轮 Showcase/生命周期脚本新增 `EXTERNAL_ACCEPTANCE_URL=http://127.0.0.1:3000` 模式，直接复用当前生产构建，不启动随机端口。生命周期 fixture 完全在隔离浏览器拦截 API，写入只进入内存；真实后端没有写入。公开 fixture 阅读改为 CSR，以免浏览器拦截无法作用于服务端请求；真实 SSR 404 与生产鉴权水合由状态脚本单独验证。Showcase 的「未开放时生产默认关闭」用例因当前 3000 明确开放 test 样板页跳过，本轮不将其记为通过，未修改开放控制。原独立服务测试模式保留，但本轮没有运行。

首次状态页脚本使用了与真实页面不符的标题定位而超时，校正后通过；首次生命周期回归因上述真实 CSR 404 问题失败，修复后两项通过。没有删除相应错误、权限或状态断言。Showcase 验证有 0 pageerror/0 hydration/0 业务 API 请求；状态检查同样 0 pageerror/0 hydration。

生产验收继续在 **http://127.0.0.1:3000/**（本轮最新生产构建，Showcase test 环境），后端 **9333** 保持原本配置。请刷新已有页面以加载新构建；不用切换端口。本地配置文件和维护者架构文档的 SHA256 与任务开始一致。没有合并、推送或自动进入阶段 2。

### 修改清单

源码清单位于 `frontend/app`，省略前缀的条目也相对该目录；文档位于 `docs/testing`。没有删除文件、增加依赖或后端修改。

- 第一批：`frontend/app/assets/css/main.css`、`pages/blog/[slug].vue`、`middleware/auth.global.ts`、`utils/articleHtml.ts`；`test/articleHtml.test.ts`、`authHydration.test.ts`、`articleFirstClick.integration.mjs`、`articlePerformance.integration.mjs`。
- 第二批：`frontend/app/components/SurfaceCard.vue`、`ProjectCard.vue`、`TechStack.vue`、`pages/index.vue`；`test/homeMotion.integration.mjs`。
- 第三批：`frontend/app/components/StatusPanel.vue`、`error.vue`、`pages/forbidden.vue`、`utils/historyPath.ts`；`test/historyPath.test.ts`、`statusPages.integration.mjs`；复用既有 `test/designShowcase.integration.mjs` 和 `blogLifecycle.integration.mjs`。博客详情的 CSR 404 与性能脚本末段检查在本批补充。
- 交付文档：本报告、截图索引，以及 [性能数据](performance/site-visual-stabilization-20261011/) 和 [浏览器截图/连续帧](screenshots/site-visual-stabilization-20261011/README.md)。

### 复测命令与取舍

在 frontend 设置上述本机 Playwright/Edge 路径，以及 `EXTERNAL_ACCEPTANCE_URL=http://127.0.0.1:3000` 后：

```text
npm run typecheck
npm run test
npm run build
node app/test/homeMotion.integration.mjs
node app/test/statusPages.integration.mjs
node --test app/test/designShowcase.integration.mjs
node --test app/test/blogLifecycle.integration.mjs
```

普通 HTML 阅读器仍保留全部正文 DOM，因此这是首屏布局和图片调度修复，不能减少总内容存储体积。公开内容缓存保留到当前 Nuxt 应用，GC 后逐步从约 5.1MiB 升到 6.9MiB 是不同文章缓存积累，不宣称内存零增长；离开后的节点恢复在 282–285 个。`content-visibility` 使用估计高度并记忆实际尺寸，没有图片原尺寸时仍可能出现加载后的局部位移；不捏造图片宽高，也没有隐藏内容或禁止滚动。后续 Markdown 阅读阶段应继续处理源图片尺寸与长文阅读，保持资源权限。

本次隔离 Edge 未直接复现整机卡死。实测已降低真实文章首次布局阻塞并验证恢复空闲，**仍待维护者在原 Edge 会话确认该严重症状消失，本报告不替代人工验收**。若仍出现整机失去响应，须继续采集该环境的浏览器/GPU 证据，不能把它归为验收通过。下一阶段仍等待维护者确认。

提交顺序：第一批 `1c7fdb6`、第二批 `ab530bf`；第三批以本报告所在提交为准，可用 `git log feature/acceptance-environment..feature/site-visual-stabilization --oneline` 查看。父分支始终是 `feature/acceptance-environment`，不是 main。
