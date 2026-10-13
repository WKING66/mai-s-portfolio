# 阶段 2.1.2 文件清单

父分支 feature/blog-reader-performance-responsive，基点 d98708e；功能分支 feature/blog-reader-performance-mobile。A=新增，M=修改。本轮没有删除功能文件，没有修改后端业务。维护者提交 43a0f33 中的 Worker 格式修改、后端修改及导入架构文档不属于本轮功能。

|状态|源码/脚本|
|---|---|
|M|[frontend/app/app.config.ts](../../frontend/app/app.config.ts)|
|M|[frontend/app/assets/css/blog-code.css](../../frontend/app/assets/css/blog-code.css)|
|M|[frontend/app/assets/css/blog-reading.css](../../frontend/app/assets/css/blog-reading.css)|
|M|[frontend/app/assets/css/design-showcase.css](../../frontend/app/assets/css/design-showcase.css)|
|M|[frontend/app/assets/css/design-tokens.css](../../frontend/app/assets/css/design-tokens.css)|
|M|[frontend/app/assets/css/main.css](../../frontend/app/assets/css/main.css)|
|M|[frontend/app/components/AdminBlogList.vue](../../frontend/app/components/AdminBlogList.vue)|
|M|[frontend/app/components/AdminProjectList.vue](../../frontend/app/components/AdminProjectList.vue)|
|M|[frontend/app/components/SiteHeader.vue](../../frontend/app/components/SiteHeader.vue)|
|M|[frontend/app/components/blog/BlogChapter.vue](../../frontend/app/components/blog/BlogChapter.vue)|
|M|[frontend/app/components/blog/BlogCodeBlock.vue](../../frontend/app/components/blog/BlogCodeBlock.vue)|
|M|[frontend/app/components/blog/BlogMath.vue](../../frontend/app/components/blog/BlogMath.vue)|
|A|[frontend/app/components/blog/BlogMermaid.vue](../../frontend/app/components/blog/BlogMermaid.vue)|
|M|[frontend/app/components/blog/BlogRenderer.vue](../../frontend/app/components/blog/BlogRenderer.vue)|
|M|[frontend/app/components/blog/BlogToc.vue](../../frontend/app/components/blog/BlogToc.vue)|
|M|[frontend/app/components/blog/BlogTocTree.vue](../../frontend/app/components/blog/BlogTocTree.vue)|
|A|[frontend/app/components/showcase/ShowcaseDeviceInfo.vue](../../frontend/app/components/showcase/ShowcaseDeviceInfo.vue)|
|A|[frontend/app/constants/blogMermaidFixture.ts](../../frontend/app/constants/blogMermaidFixture.ts)|
|M|[frontend/app/pages/design-showcase.vue](../../frontend/app/pages/design-showcase.vue)|
|A|[frontend/app/test/blogDeviceMatrix.integration.mjs](../../frontend/app/test/blogDeviceMatrix.integration.mjs)|
|M|[frontend/app/test/blogMath.test.ts](../../frontend/app/test/blogMath.test.ts)|
|A|[frontend/app/test/blogMermaid.integration.mjs](../../frontend/app/test/blogMermaid.integration.mjs)|
|A|[frontend/app/test/blogMermaid.test.ts](../../frontend/app/test/blogMermaid.test.ts)|
|A|[frontend/app/test/blogReadingExperience.integration.mjs](../../frontend/app/test/blogReadingExperience.integration.mjs)|
|M|[frontend/app/test/siteResponsive.integration.mjs](../../frontend/app/test/siteResponsive.integration.mjs)|
|M|[frontend/app/utils/blogChapters.ts](../../frontend/app/utils/blogChapters.ts)|
|M|[frontend/app/utils/blogMath.ts](../../frontend/app/utils/blogMath.ts)|
|A|[frontend/app/utils/blogMermaid.ts](../../frontend/app/utils/blogMermaid.ts)|
|M|[frontend/app/utils/codeHighlight.ts](../../frontend/app/utils/codeHighlight.ts)|
|M|[frontend/app/utils/markdown.ts](../../frontend/app/utils/markdown.ts)|
|M|[frontend/package-lock.json](../../frontend/package-lock.json)|
|M|[frontend/package.json](../../frontend/package.json)|
|A|[scripts/windows/enable-dev-lan.ps1](../../scripts/windows/enable-dev-lan.ps1)|

文档：blog-experience-stage212.md、hotspot-device-acceptance.md、本清单。性能证据位于 performance/blog-experience-20261012，最终可见录屏/追踪为 delivery；同条件对照为 baseline-no-video / final-performance。截图位于 screenshots/blog-experience-20261012；完整设备截图以 devices-final/results.json 与 INDEX.md 为准。历史中断与失败证据保留，不能将历史 devices 目录当作完整矩阵。
