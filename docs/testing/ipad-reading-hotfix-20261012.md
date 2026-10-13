# iPad 目录与桌面长文阅读修复

父分支 `feature/blog-reader-performance-mobile`，基点 `85ea3ff`；修复分支 `feature/ipad-toc-desktop-reading-hotfix`。维护者两处 Portfolio 后端未提交修改保留。本轮不改正文、API、权限、资源访问或编辑保存业务，仅使用前端 3000、后端 9333。

## 验收反馈与复现

维护者反馈：相同 Edge /《数据结构与算法笔记》用例再次卡顿，程度减轻但影响体验；iPad 9 / iPadOS 18.6.2 / Edge 在横竖屏打开目录仅显示标题。

此前 Chromium 设备矩阵不能代表 iOS 内核，而且仅检查了链接存在和弹层边界，没有断言内部列表的实际高度、可点击命中。这一验收缺口已经补上。旧的“模拟通过”结论不能当成 iPad 实机通过，桌面性能也重新进入待验收状态。

隔离官方 Playwright WebKit 18.5 复现：810×1080、1080×810、320×568，两主题下均有 117 项链接，但列表高度为 **0px**，弹层高度只有 132px（窄屏 116px），与维护者截图一致。最新 WebKit 26.5 未复现该高度问题。18.5 为兼容对照，不等于 18.6.2 真机。

根因是弹层 `height:fit-content` 与多层 `flex:1` 的不定高度组合，旧 WebKit 将滚动列表压缩至零。改为扣除 Header、底部留白和 Safe Area 的明确视口高度，保留 vh 回退和 svh 覆盖。没有机型判断、全局裁剪或业务 DOM 操作。修复后旧 WebKit 列表高度为 859/589/363px，可滚动到末项并点击正文定位。

## 水合与可访问性

临时启用 Vue 生产水合详细诊断，实际节点显示两类问题：

- 媒体查询在延后水合时已经获取触控状态，服务端目录分支按钮与客户端 span 首次结构不同。目录自己的 mounted 标志保证首次结构一致，挂载后才应用输入能力与窄屏状态。
- 异步章节的 useId 前缀/顺序和下载复选框 ID 在 SSR/客户端不同。章节 body 复用阅读器版本键与标题 ID，Showcase 阅读/预览使用各自稳定键；下载复选框有稳定文章 ID。保持 Nuxt UI 组件、标签和 aria-controls 关联，没有第二套状态管理。

新回归检查所有章节按钮所关联 body 唯一、存在并属于同节，目录首项能被 hit-test 命中，列表有可阅读高度，末项能实际点击。详细诊断构建已确认水合警告为零；验收服务随后恢复正常生产编译配置。

## 桌面性能证据

故障文章仍为 56,183 字符、38 个资源，正文约 7696 个元素。本次增加整篇正文两次向下/向上滚轮阅读，未只测首屏。

原目录在正文 ResizeObserver 回调后逐次扫描所有 117 个标题。跳过内容重新参与布局及图片加载使长文高度在滚动中反复变化：全篇往返过程中触发约 130 次目录尺寸回调，累计 **15,210 次**标题几何读取。折叠仍没有全文解析、重建章节树、无关重挂载或重新高亮；不是据用户假设直接重写整个阅读器。

现在尺寸变化只使缓存版本过期，二分查找时按需读取该次需要的标题；只有正文结构改变才重新收集标题元素。目录高亮、内部跟随、显式跳转锁和手动解除规则保留。候选同场景标题读取 **1184 次**，Layout 累计 239.1→192.9ms、Style 138.9→112.4ms，最大 rAF 间隔 78.9→54.6ms。这里只说明已消除重复测量成本，不能用这些数字宣告真实体验已通过。

诊断性关闭目录测量的隔离实验仍记录到无脚本的约 1s 帧间隔；当时未记录页面可见性，无法将该异常确定归因于网页或窗口调度。原始数据保留，不把关闭功能当最终修复，也不宣称目录是全部卡顿的唯一原因。最终追踪补充页面 visibilitychange、完整 Trace、两种桌面尺寸及滚动期间解析/挂载计数。

## 测试与证据

最终结果见后文。设备截图始终标记为内核/视口模拟，不冒充实机。

- [目录复现与修复截图](screenshots/ipad-reading-hotfix-20261012/)：`before18`、`fixed18`、`final18`、`final26`、`final-edge` 各自 JSON 包含内核版本、主题、CSS 视口、DPR 和实际列表高度。
- [性能追踪](performance/ipad-reading-hotfix-20261012/)：`before-sustained` 为基线，最终记录为 `progressive-1280` / `progressive-1920`。`candidate-sustained` 为局部测量候选，`diagnostic-no-toc` 仅为隔离实验；中间 JSON 留在证据目录，中间大型原始 Trace 保留于项目忽略目录 `.local-backups/ipad-hotfix-diagnostic-traces`，没有删除。
- [热点使用说明](hotspot-device-acceptance.md)：地址随 DHCP 改变；同源 API 代理继续访问 127.0.0.1:9333。

方法参考：[MDN flex-basis](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/flex-basis)。WebKit 18.5 来自 [Playwright 1.53.2 官方浏览器清单](https://raw.githubusercontent.com/microsoft/playwright/v1.53.2/packages/playwright-core/browsers.json)，测试工具安装在忽略目录，没有改变项目依赖。

## 修改清单

|文件|职责|
|---|---|
|frontend/app/assets/css/blog-reading.css|明确目录弹层可用高度|
|frontend/app/components/blog/BlogToc.vue|按需二分测量，稳定首次水合结构|
|frontend/app/components/blog/BlogChapter.vue|稳定章节 body ID|
|frontend/app/components/blog/BlogRenderer.vue|提供既有阅读器键用于 ID|
|frontend/app/utils/blogChapters.ts|补充章节注入契约中的 ID 前缀|
|frontend/app/pages/blog/[slug].vue|下载复选框 ID/label 一致|
|frontend/app/components/showcase/ShowcaseArticle.vue|静态样板稳定阅读器键|
|frontend/app/components/showcase/ShowcaseEditor.vue|预览样板稳定阅读器键|
|frontend/app/test/blogTocWebkit.integration.mjs|WebKit/Edge 实际高度、命中、点击和 SSR 回归|
|frontend/app/test/blogReadingExperience.integration.mjs|全篇滚动、尺寸配置、观察器/可见性计数与 Trace|

本报告及专属截图/性能目录为新增证据文件；没有删除功能组件，没有改后端源码。

## 最终结果与人工反馈

维护者实际复测：**iPad 9 / iPadOS 18.6.2 / Edge 的目录正常显示、滚动并点击跳转；电脑 Edge「暂未发现明显卡顿现象」**。这是维护者的实机结果，没有把 Windows WebKit 模拟冒充 iPad。

逐节缓存的最后优化：已准备好的叶章节立即启用既有实测高度缓存，不再等待全篇完成后才跳过离屏内容。仍未准备、折叠的内容不套用旧测量值；宽度变化继续自然排版一次后刷新。相同 1280px 不录屏对照首次累计 Layout 从 688.7 降至 586.8ms，重新进入全部准备约 1534→993ms。首次最大帧间隔只从 139.4→133.4ms，不能声称这一项已经彻底消除。

|检查|实际结果|
|---|---|
|类型检查|最终源码通过|
|Vitest|31 文件、287 项通过|
|生产构建|通过；正常项目编译配置，保留已有 DEP0155 依赖警告|
|WebKit 18.5 / 26.5 / Edge 目录|3 内核×3 视口×2 主题，共18组：实际高度、命中、点击、章节 aria-controls、SSR通过|
|最后逐节缓存回归|旧 WebKit 再测6组，阅读1440/768/390/320px×2主题8组，通过；警告为零|
|真实故障长文追踪|1280×800、1920×1080，两次全篇往返，折叠/展开/跳转/主题/离开/返回，通过无额外全文解析和章节挂载的断言|
|公式、Showcase|现有公式8场景通过；Showcase11项通过、1项生产关闭分支跳过（验收服务器为test模式）|
|维护者人工|iPad目录通过；电脑Edge暂未发现明显卡顿|
|未重复项目|34设备完整矩阵、后端/数据库生命周期；前次证据保留，本轮没有重跑就不写成本轮通过|

早期检查命令误用不存在的 test:unit 脚本及错误 Vitest 配置路径，已改为项目实际 npm run test，最终通过；不把这些调用失败隐去。详细水合诊断曾发现前述结构/ID问题，修复后旧、新内核均归零。

最终1280px不录屏记录，全篇往返最大rAF间隔66.6ms，大章节展开48.5ms；仍保留首次及离开时的短暂长任务。1920px录屏记录最大间隔较大：首次181.8ms、全篇109.1ms、展开109.0ms、代码菜单151.6ms、离开230.3ms。该宽屏录屏初段同时执行了类型检查，且录屏增加图像回读成本，不当成同条件基准或产品独立成本。两场景记录可见性均为visible，后续人工无明显卡顿的反馈独立记录。rAF不是物理GPU FPS，Raster累计不等于GPU利用率；详细JS、Style、Layout、Paint与Raster见summary.json。

每次折叠、展开及目录跳转，Markdown解析、章节树、KaTeX、Shiki增量均为零，无无关章节挂载/卸载。最后全篇往返标题读取在约1200次范围内，新增小于3000次的长期回归断言；正常路由离开仍需要卸载，不据此声称任意长文章都没有剩余成本。

证据入口：[截图索引](screenshots/ipad-reading-hotfix-20261012/INDEX.md)、[检查汇总](performance/ipad-reading-hotfix-20261012/checks.json)、[阶段成本](performance/ipad-reading-hotfix-20261012/summary.json)、[最终Edge录屏](performance/ipad-reading-hotfix-20261012/progressive-1920-visible-edge.webm)。原始基线及最终Trace均为gzip文件，解压后导入DevTools Performance。

当前服务：本机 **http://127.0.0.1:3000/**，热点 **http://192.168.43.236:3000/**，后端仍为127.0.0.1:9333。IP可能随DHCP改变，未更改已有防火墙或生产安全配置。

代码提交 **21082ea**；文档/证据另作独立提交。按维护者“每阶段完成后合回父分支”的既定要求，仅快进回 `feature/blog-reader-performance-mobile`，不合并main、不推送。后续继续观察长时间反复阅读和不同机器的负载；不进入阶段3。
