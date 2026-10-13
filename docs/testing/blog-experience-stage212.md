# 阶段 2.1.2：阅读体验修复记录

## 基点与边界

父分支 `feature/blog-reader-performance-responsive`，基点 `d98708e`；本轮分支 `feature/blog-reader-performance-mobile`。维护者的导入架构文档、Worker 格式调整，以及本轮期间新增的后端修改/暂存删除均不纳入提交。未修改数据库、正文格式、权限、发布规则或正式编辑器。

验收服务仅使用 Nuxt **3000** 与 Spring Boot **9333**。测试通过浏览器内 fixture 覆盖 API 返回时，不会写维护者真实文章。

## 性能证据与修复

实际故障文章《数据结构与算法笔记》：56,183 个 Markdown 字符、38 个资源，渲染后 123 个标题、10 个代码块、110 个公式、38 张图片、13 张表格、约 7,696 个正文元素。组件计数与 Performance Trace 均在真实隔离可见 Edge 中采集。

没有发现折叠导致全文重解析：折叠/展开时 Markdown、章节树、KaTeX、Shiki 计数不增加，章节挂载/卸载计数为零。主要成本是浏览器样式/布局以及 DOM 移除；不是已证实的全量 Vue 重渲染。

修复内容：

- 目录由每帧扫描全部标题改为尺寸变化时缓存位置、滚动时二分定位。正文折叠的后代不参与位置测量，目录独立折叠不改变正文定位集合。
- 目录保留稳定行与 key，只有选中行和必要折叠状态更新；选中项越界时只滚动目录内部，不调用会连带滚动正文的 `scrollIntoView`。
- 章节结构和 AST 保持原有不可变缓存；首次客户端准备包括标题控件，每帧最多两节。SSR 完整内容和 Vue 官方空闲/交互水合策略保留。
- 叶章节采用实测高度的 `content-visibility:auto`，折叠采用 `content-visibility:hidden`、零块高度与 `inert`；旧浏览器降级为 `display:none`。没有大型高度动画。
- 尺寸观察共用一个 ResizeObserver，直接维护组件持有的 CSS 尺寸值，不增加章节响应式更新。聚焦代码控件时取消该叶的 paint containment，避免块内 Select 弹层被裁剪。
- KaTeX 仍使用 `trust:false` 与标签/属性白名单，公式生成物改为 Vue 官方静态 VNode。保留 HTML 排版及可访问 MathML，不接受作者 HTML；不是把整篇 Markdown 转成 HTML 字符串替换。
- Shiki 保持同一个实例与既有高亮预算/缓存，改用官方 Oniguruma 引擎。移除代码每个 token 的颜色过渡，控件仍保留交互反馈。

同条件（1280×800、可见隔离 Edge、不录屏、不启动 CPU Profiler）初始对照：

| 操作 | 基线最大 rAF 间隔 | 候选最大 rAF 间隔 | 基线/候选补充数据 |
| --- | ---: | ---: | --- |
| 首次准备全文 | 248.5ms | 169.8ms | 全部准备 4755/3788ms；期间渐进显示 |
| 连续滚轮阅读 | 54.6ms | 48.4ms | 标题读取 7371/1755 次附近，随图片加载有波动 |
| 快速上下滚动 | 78.7ms | 18.3ms | 无连续主线程冻结 |
| 大章节展开 | 297ms | 72.7ms | Layout 73.5/14.0ms，Style 130.3/83.5ms |
| 末尾目录跳转 | 36.4ms | 12.2ms | 无全文解析/章节重挂载 |
| 代码主题 | 121.3ms | 102.9ms | Style 210.6/114.7ms；菜单仍存在短暂布局成本 |
| 离开文章 | 527.2ms | 242.4ms | 仍存在大量原生 DOM 移除成本 |
| 重新进入 | 91.1ms | 78.8ms | 全部准备 1529/2047ms；以分散工作换较短帧间隔 |

CPU Profile 诊断中原生 `removeChild` 采样约 128ms；没有据此重写官方 Prose。候选仍有首次准备、菜单及离开时的短暂帧间隔，不能声称所有长任务或掉帧消失。

维护者在 3000 的实际 Edge 反馈为「操作已流畅」，同时指出目录高亮未随目录内部滚动。本批修复该问题，最终目录跟随结果单独回归。

## 证据的解释范围

`performance/blog-experience-20261012/` 保留 Trace、Long Task、Long Animation Frame、Vue 更新/挂载计数与 GPU 信息。`baseline-no-video` 与 `final-performance` 为同条件初始对照；后续录屏用于展示操作，不与不录屏数据直接作性能比较。

设备实际 GPU 为 NVIDIA RTX 4050 Laptop、ANGLE D3D11。Raster/Decode/Composite 数据来自 Trace；线程事件累计时间不等于物理 GPU 利用率。rAF 间隔衡量主线程帧回调，不能直接当作 GPU FPS；DroppedFrame 可能涉及多个 frame sink，不以原始事件数伪造掉帧百分比。可识别 Scripting 区间减去嵌套 Style/Layout/Paint，仍可能遗漏 Runtime 调用中的原生工作，以 CPU Profile 和长任务互相校验。

## 本批验证

- Nuxt 类型检查：通过。
- Vitest：30 个文件、284 项通过，包括静态公式的实体/TeX 保真与作者 HTML 拒绝。
- Nuxt 生产构建：通过；依赖存在 `DEP0155` 包导出弃用警告。
- 阅读交互回归：1440、768、390、320px，浅/深两主题通过；含焦点恢复、瞬时定位、目录/正文独立折叠、子节状态保留、代码复制/主题/展开状态与无整页横向溢出。
- 沙箱内首次构建和 Vitest 因 `realpath EPERM` 失败，正常权限重跑通过；不是产品检查失败或未执行冒充通过。
- 此处为第一批结果；Mermaid、设备矩阵、真机与 LAN 的最终结果见后文。维护者人工反馈不替代其他未执行回归。

## Mermaid 批次

原阅读器关闭默认插件且未注册 Mermaid 围栏插件，所有围栏进入 `pre`/Shiki 路径。Comark 0.7 自带的 Vue Mermaid 组件使用 optional peer `beautiful-mermaid`，主题监听方式也与本项目独立主题不同，因此复用官方 **Comark core Mermaid 插件**识别围栏，映射到受控 `BlogMermaid`，使用官方 `mermaid@12.1.0` 渲染。SVG 过滤使用 `dompurify@3.4.16`，没有新建 Markdown/图表解析器。

组件只在接近阅读位置时加载图表库；串行渲染队列与 16 项有界安全 SVG 缓存限制重复工作。主题颜色直接读取既有 `--site-*` 变量，支持 Showcase 的局部预览；一个颜色组合首次生成 SVG，随后同组合复用。滚动、章节折叠不重新解析图表。

安全边界为 `securityLevel:strict`、禁用 HTML labels/自动启动、固定安全配置、字符/边/节点/结果大小限额、DOMPurify SVG 过滤、内部 URL 与 CSS 作用域检查。拒绝文档安全配置覆盖和外部图像；不调用 Mermaid 返回的交互绑定。图表实例 ID、内部引用和默认描边关键帧均独立命名。复杂或不支持的图表明确显示错误及完整源码，不写回 Markdown。

浏览器验证发现过两类误拒绝：官方默认关键帧属于全局 CSS 规则；官方默认 Neo 样式引用组件内部滤镜 `url(#id)`。通过隔离关键帧名称、仅允许内部片段 URL 修复，保持禁止外部样式引用。首次自动化还因误假设浅色按钮在初始浅色状态存在而超时；已修正测试定位。最终图表验证通过，不把这些早期失败隐藏为通过。

验证结果：31 个测试文件、287 项单测通过；生产构建通过。真实 Edge 浏览器在 1280×900 与 390×900 视口验证流程、时序、类、状态、ER 图，浅色→深色→浅色，复制/源码、安全指令拒绝、HTML/危险链接无执行、无效语法回退、超宽图表内部滚动及无整页横向溢出，未发现 Hydration/pageerror。第二次切回浅色的渲染计数保持 12，不增加解析。截图与元信息位于 `screenshots/blog-experience-20261012/mermaid/results.json`。

图表可直接在开发/测试专用 `/design-showcase#mermaid-reading` 验收；样例不写入维护者文章。超过安全限额、外部图像与配置指令当前降级为可复制源码。iOS/HarmonyOS 实机结果另列，Edge Chromium 模拟不能代表真实 WebKit。

## 全局响应式方案与参考源码

已读取维护者提供的 [参考项目源码](https://gitee.com/mrzym/stable-version-of-blog)，固定审查版本为 `57d1429`。`blog-v3/src/styles/variable.scss` 使用 14px 基础字号；`styles/base.scss` 的页面容器为百分比宽度、居中 max-width、rem 间距，768px 区分导航和布局；`views/article/article.vue` 使用 Element Plus 百分比栅格（xs/sm/md），并非按手机型号逐个适配，也没有用 resize 动态缩放根字号。

当前项目采用同样的内容适配原则，继续复用 Tailwind、Grid/Flex 与 Nuxt UI：全局 `--site-width` / `--site-reading-width` 用 rem，`--site-gutter` 用 clamp；容器与 Grid/Flex 子项允许收缩，宽代码、表格、公式、图表内部滚动。移除 body 的 320px 最小宽度及重复窄屏容器间距。代码工具栏允许自然换行，弹层统一限制可用视口宽度。保留少量导航/管理/目录形态断点和输入能力查询，不新增机型分支。字体不随 vw 无下限缩小，保留用户字号设置与可见键盘焦点。

Header 使用一个 ResizeObserver 提供实际高度供锚点、目录使用，不在滚动中反复测量；短屏或字号放大时目录顶部占位上限为 25svh，宽度随可用视口收缩，保留露出的正文与 Safe Area。Showcase 继续独立主题预览。设备名称只进入测试矩阵，不进入产品布局代码。

参考站中关闭焦点轮廓、全局裁剪与背景滤镜等规则未复制。本次参考网页读取曾返回 502、浏览器工具自动授权检查超时，没有声称已看到其现场视觉；随后成功读取的是上述用户指定源码。

真机与局域网说明见 [热点验收](hotspot-device-acceptance.md)。Mate60 Pro / Edge：维护者报告通过；iPad 9：设备不在手边，未验证。其他设备模拟不能代替这些真实硬件结果。

### 宽度变化时的阅读定位

设备矩阵补充了在同一故障文章中旋转，而非只重新打开横屏页面。Pixel 8 的初始断言未考虑末节后不足一屏、浏览器最大滚动距离；修正为检查 `scrollingElement.scrollHeight - clientHeight`，没有改变阅读器来满足不可能的顶端对齐。

平板旋转另有真实 117px 漂移：定位时滚动 78401，后续布局的最大滚动为 78518。关闭滚动锁后再定位的候选没有改善，已经撤回。取消章节可见性跳过，或强制重新求值实测尺寸声明的隔离实验，均能滚动至 78518。最初怀疑宽度缓存，但完整矩阵再次失败；后续记录显示宽度一直为 760px，正文高度从 78646.44 增至 78763.31。样式源中已是显式实测值，跳过内容的计算样式仍可能保留先前值，不能据此声称服务加载旧版本。证据支持延后布局改变总高度，不能把所有偏差仅归因于宽度、图片或 Vue 全文更新。

正式修复保留 `content-visibility:auto`，声明采用实测高度；现有共享 ResizeObserver 同时观察正文宽度，宽度变化时让内容自然排版一次、收集新高度再恢复跳过。折叠内容保留隐藏，在重新展开得到实际高度后恢复跳过。另外复用目录原有的显式定位锁：仅在正文/导航尺寸变化时，以单个目标标题和真实最大滚动距离稳定位置。滚轮、触摸、指针和阅读键立即解除锁定，并在解除时刷新位置缓存；正常滚动不写正文位置，不逐帧扫描标题。没有高度动画、全文解析、章节树重建或相互写入的 watch 循环。最终矩阵与性能结果以本报告末尾的交付回归为准。

## 方法来源（性能）

已访问维护者提供的 [万级数据量性能文章](https://www.cnblogs.com/wzzkaifa/p/19095678)，采用分散工作、稳定结构和可见内容跳过的排查思路；变高长文章没有机械套用固定行虚拟表格。

参考 [Chrome Performance](https://developer.chrome.com/docs/devtools/performance/reference)、[Rendering 诊断](https://developer.chrome.com/docs/devtools/rendering/performance)、[content-visibility](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/content-visibility)、[Shiki 正则引擎](https://shiki.style/guide/regex-engines)。

## 最终交付回归（2026-10-12）

当前 3000 运行最终生产构建候选，后端继续沿用 9333；没有额外验收服务。最终可见隔离 Edge 为 153.0.4234.48，1280×800，正常动画设置，录制真实滚轮、点击及路由操作。自动化驱动的可见窗口与维护者人工操作分别记录。

|检查|实际结果|
|---|---|
|Nuxt 类型检查|通过，最终构建对应源码|
|Vitest|31 文件、287 项通过|
|生产构建|通过，保留依赖 DEP0155 弃用警告|
|后端 DocumentPipelineTest|16 项通过；初次默认 JDK 不支持 release 21，改用现有 JDK21 重跑通过|
|数据库 BlogDocumentLifecycleTest|本轮未运行：缺少隔离测试数据库配置；不能用浏览器 fixture 代替数据库测试|
|Playwright 阅读、公式、Worker、安全、Mermaid|通过；包括两主题、目录跟随/定位/折叠、代码主题与复制、SSR/Hydration、危险 HTML/URL、安全图表回退|
|导入 Hotfix、Slug 与生命周期|浏览器隔离 fixture 通过；不写维护者真实文章，保留保存/发布/下架互斥及已发布 slug 锁定|
|Showcase|11 项通过、1 项跳过：测试模式外部服务器不执行生产关闭分支；并非该门禁已验证通过|
|全站响应式|13 个宽度（280～1440，含断点两侧）×2 主题×15 页面，共 390 组，通过|
|200% 根字号|280、320、768、1440px×2 主题×15 页面，120 组通过|
|设备矩阵|34 配置、488 张截图，两主题、横竖屏、触摸、目录/主题选择/图表交互通过，无整页水平溢出或水合错误|
|真机|维护者 PC Edge 操作已流畅；Mate60 Pro / Edge 报告通过；iPad 9 不在手边，未实测|

最终录屏测量：连续阅读最大 rAF 间隔 54.7ms，快速上下滚动 18.2ms，大章节折叠 54.5ms、展开 60.6ms，末尾目录定位 12.2ms，离开 145.5ms。首次准备仍有 181.8ms、代码主题菜单仍有 121.2ms 的最大间隔，不能声称所有长任务消失。录屏增加图像回读成本，不能与前面的不录屏基线混作严格对照；维护者确认的真实操作体验仍是验收依据。

折叠、展开、快速目录切换的全文解析/章节树/Shiki/KaTeX 增量及章节挂载/卸载均为零。离开路由正常卸载 117 个章节，重新进入正常挂载并命中解析缓存；不把生命周期必需的挂载误判为折叠全量更新。首屏正文约 7696 个元素，CDP 全页 Nodes 约 23154（包含文本等节点，两者口径不同）。最终录屏返回后未 GC 的 Nodes/Heap 会暂时增至 46141/62.7MB；独立三轮路由测试执行 GC 后，390px 后两轮堆为 31.65/31.95MB，1440px 同样通过增长小于 5MB 的检查。短测试不能证明无限期无内存泄漏。

直接 SSR 打开真实故障文章：1440px 全部准备约 7.70s，domInteractive 2564ms，记录到 134ms 长任务；390px 全部准备约 5.52s，domInteractive 698ms，长任务 79/153ms。全部准备时间包含渐进内容，不等于整段页面不可交互；累计 Layout 约 763/801ms。原始指标与追踪保留，不只挑选改善项。

### JS、布局与绘制的对照

同条件不录屏 Trace 的事件累计时间（ms），JS 为主线程区间并集扣除嵌套渲染；Raster 为线程累计，不能解释成真实 GPU 占用：

|场景|JS 基线→候选|Layout 基线→候选|Paint 基线→候选|Raster 基线→候选|
|---|---:|---:|---:|---:|
|首次准备|882.9→795.7|1019.7→1029.3|54.4→83.3|16.6→22.7|
|持续滚动|412.7→105.7|33.7→40.4|79.7→83.1|206.1→196.5|
|大型章节展开|77.3→30.8|73.4→14.0|7.8→3.7|0.5→0.6|

首次累计布局和绘制并没有全面下降；主要改善是减少重复标题扫描及局部展开成本、分散首次工作。Style、Decode、Composite、原始 DroppedFrame 与每阶段 Vue 更新计数完整保留于 JSON/Trace。没有凭 rAF 推导物理 GPU FPS 或掉帧百分比。

### 设备截图与证据入口

- [最终截图索引](screenshots/blog-experience-20261012/INDEX.md)：每张图片记录设备配置、CSS 视口、DPR、方向、主题、浏览器及路径。
- [最终设备矩阵](screenshots/blog-experience-20261012/devices-final/results.json)：iPhone SE/XR/12 Pro/14/14 Plus/14 Pro Max/15/15 Pro Max/16/16 Pro/16 Pro Max、Moto G4、Pixel 7/8、Galaxy Tab S4、iPad gen7 与对应横屏，以及两个通用 Android 宽视口。设备名称只用于测试，产品没有机型分支。
- [可见 Edge 录屏](performance/blog-experience-20261012/delivery-visible-edge.webm)、[最终交互指标](performance/blog-experience-20261012/delivery-experience.json)、[最终 Trace](performance/blog-experience-20261012/delivery.trace.json.gz)。解压后导入 DevTools Performance。
- [同条件 JS/布局/绘制分析](performance/blog-experience-20261012/cost-summary.json)、[SSR/三轮 GC](performance/blog-experience-20261012/direct/direct-reading.json)、[检查汇总](performance/blog-experience-20261012/checks.json)。
- [Mermaid 类型与主题截图](screenshots/blog-experience-20261012/mermaid/results.json)、[本轮修改文件](blog-experience-stage212-files.md)、[热点使用/防火墙撤销](hotspot-device-acceptance.md)。

完整矩阵首次在 Windows 写已有 PNG 时出现 UNKNOWN errno -4094，完成 19 配置后中断。最终清单保留其中已完成且元信息匹配的 270 张原始截图，剩余 15 配置在新目录实际重跑生成 218 张；校验 488 个唯一非空文件后汇总。没有缩放图片充当另一设备，没有把中断测试写成完整通过。历史 devices 及诊断文件保留。iPad gen7 类似 CSS 尺寸不能代表 iPad 9 实机；模拟 iPhone/iPad 使用 Edge Chromium，不代表 iOS WebKit。

### 局域网与后续人工验收

当前电脑热点 IPv4 为 **192.168.43.236**，手机入口 **http://192.168.43.236:3000/**；本机 **http://127.0.0.1:3000/**，图表样例 **/design-showcase#mermaid-reading**。IP 会随热点 DHCP 改变。Nuxt 监听 0.0.0.0:3000，API 经同源代理到 127.0.0.1:9333；后端未向热点开放。临时防火墙只放行当前热点网卡、子网、Node 程序的 3000，详见撤销说明。

HTTP 公开阅读可验收。现有登录使用 WebCrypto；手机不可信 HTTP 不提供 subtle，登录/管理真机验收仍需受信任 HTTPS，未弱化生产或现有认证规则。iPad 9、iOS/WebKit 真机、虚拟键盘与可信 HTTPS 下账户/管理流程仍待维护者设备验收；模拟交互不能替代这些项目。

### 提交与边界

父分支 feature/blog-reader-performance-responsive @ d98708e，功能分支 feature/blog-reader-performance-mobile：

1. 5fe1ea4：性能局部更新与目录跟随。
2. ecbf9be：Mermaid 惰性安全渲染。
3. 维护者期间提交 43a0f33（格式优化）包含其后端/文档修改及当时本轮响应式/LAN、部分证据；保留原样，没有将该提交视为本代理独立提交或改写历史。
4. e33a427：延后布局下的阅读定位稳定性、旋转回归及热点脚本修正。
5. 最终证据与文档提交：与上述代码提交分开，提交号见 Git 历史。

没有合并 main 或推送。维护者当前 ProjectController/ProjectServiceImpl 未提交修改保留。阶段 3 未开始；当前候选交给维护者继续浏览器及平板验收，不将未实测项目宣布完成。
