# Mermaid 图表查看器

父分支 `feature/blog-reader-performance-mobile`，基点 `76b0bc1`；功能分支 `feature/mermaid-interactive-viewer`。维护者现有两处 Portfolio 后端修改保留，未更改正文、API、权限、上传或发布流程。

## 阅读与交互

- 默认按容器可用宽高等比适配并居中，保留 SVG 清晰度；大图细节通过缩放查看。
- 图表上 Ctrl＋滚轮围绕鼠标位置缩放，正文内普通滚轮继续滚动文章。加减按钮、加减键与适应容器/0键也可控制比例。
- 点击图表或全屏按钮打开 Nuxt UI 全屏弹窗；全屏可鼠标/单指拖拽、方向键移动，支持缩放、重新居中、关闭按钮及 Escape。关闭后焦点返回正文图表。
- 源码紧接标题工具栏，位于图表上方；较长源码在自身区域滚动，复制仍保留完整原文。

全屏覆盖浏览器内容区域，沿用 Nuxt UI 的滚动锁、焦点管理和现有稳定滚动条占位，不调用操作系统全屏 API。触屏缩放使用加减按钮，暂未提供双指捏合。不会把视图比例或位置写入 Markdown。

共用 `BlogMermaidCanvas` 负责局部视图状态和原生 Pointer/Wheel 事件。缩放/平移只改变 plane 的 transform，不重新运行 Mermaid；ResizeObserver 仅在视口大小变化时适配。全屏复用已安全生成的 SVG，重新隔离内部 ID、引用及样式，避免正文和弹窗同时存在时冲突。没有引入新依赖、解析器、UI 框架或主题状态系统。

全屏 Portal 复制图表局部 Nuxt UI 语义变量，Showcase 的独立深色预览也不会继承错误的全站亮色。DOMPurify、严格模式、图表限额及不可信配置拒绝保持原样；所有注入仍来自原有安全渲染边界。

## 回归与证据

最终验证通过。截图仅为真实浏览器的视口/内核模拟，不能代表真实硬件测试。交互样例在 `/design-showcase#mermaid-reading`，安全场景使用浏览器隔离 fixture，不写维护者文章。

|检查|实际结果|
|---|---|
|Nuxt类型检查|通过|
|Vitest|31文件、287项通过|
|生产构建|通过；已有DEP0155依赖导出弃用警告保留|
|Edge交互|1280/810/390/320×900、两主题，共8组通过|
|WebKit18.5交互|810/1080/390×900、两主题，共6组通过；属于Windows内核模拟，不是iPad9实测|
|触控|Edge窄屏场景原生CDP touch事件拖拽通过；未冒充真实手机|
|Mermaid类型/安全|流程、时序、类、状态、ER、错误回退、超宽图、复制与危险HTML/配置拒绝通过|
|SSR与主题|一致的客户端占位、无水合警告，Portal局部主题与SVG内部ID隔离通过|
|视图成本|缩放、平移、源码切换和反复打开全屏不增加Mermaid解析次数|

保留Markdown初始化与懒渲染，未重复运行无关后端或数据库测试。真实设备上的新图表交互仍待维护者复验，不能引用前次目录验收来宣布本功能实机通过。

截图与完整尺寸元信息见 [截图索引](screenshots/mermaid-viewer-20261012/INDEX.md)；包含浅/深、源码在上、全屏适配和拖拽截图，以及安全回退样例。

本地入口 **http://127.0.0.1:3000/design-showcase#mermaid-reading**；热点入口 **http://192.168.43.236:3000/design-showcase#mermaid-reading**。后台仍仅在9333运行，热点IP可能随DHCP改变。

实现中修正的检查问题：

- 初次类型检查暴露 Nuxt UI content 类型未声明 HTML style 的字面量字段，改用具有明确事件字段的计算属性投影，未使用 any 或关闭类型检查。
- 全屏边界首次误用 innerWidth/clientWidth；现有 scrollbar-gutter 保留空间下 CSS 根布局宽度不同，已根据真实根矩形检查，没有修改全局滚动条规则来满足断言。
- 缩放锚点检查改为使用浏览器实际 wheel 客户端坐标，避开输入坐标取整及普通滚轮尚未结束的影响。
- 实际截图发现 Header 遮挡全屏标题/关闭按钮，已修正 Nuxt UI 局部弹层层级，并新增关闭按钮 hit-test 和实际点击检查。

## 修改文件

|文件|变化|
|---|---|
|frontend/app/components/blog/BlogMermaidCanvas.vue|新增共用适配、缩放和拖拽视图|
|frontend/app/components/blog/BlogMermaid.vue|源码上移、Nuxt UI 全屏、局部主题投影|
|frontend/app/utils/blogMermaid.ts|复用安全 SVG 的实例 ID 隔离函数|
|frontend/app/assets/css/blog-reading.css|图表视口、局部变换、全屏与源码滚动样式|
|frontend/app/test/blogMermaidViewer.integration.mjs|新增真实浏览器交互回归|
|frontend/app/test/blogMermaid.integration.mjs|宽图默认适配断言、独立截图输出配置|

本报告及 `screenshots/mermaid-viewer-20261012` 为新增交付证据。无功能组件删除，无依赖版本变更。
