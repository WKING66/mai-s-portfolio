# 浏览器验收素材

原生 Edge，前端 3000，后端 9333。下列图片来自真实浏览器，不是设计稿。生产构建、隔离浏览器、串行性能检查；403 画面关闭已有 Toast 后截图，未删除 Toast 功能。

|内容|桌面|移动|
|---|---|---|
|404 浅色|[查看](404-light-1440.png)|[查看](404-light-390.png)|
|404 深色|[查看](404-dark-1440.png)|[查看](404-dark-390.png)|
|403 浅色|[查看](403-light-1440.png)|[查看](403-light-390.png)|
|403 深色|[查看](403-dark-1440.png)|[查看](403-dark-390.png)|
|已登录但权限不足|[查看](403-authenticated.png)|—|
|首页卡片 Hover|[查看](home-card-hover.png)|—|
|技术栈减少动态效果|—|[静态换行](home-stack-mobile-reduced.png)|
|博客详情修复后|[查看](../../performance/site-visual-stabilization-20261011/after-article-1440.png)|[查看](../../performance/site-visual-stabilization-20261011/after-article-390.png)|

技术栈连续三帧：[帧 1](home-stack-frame-0.png)、[帧 2](home-stack-frame-1.png)、[帧 3](home-stack-frame-2.png)。[动画时间和负载数据](home-motion.json) 验证动画真实运行，且没有 Long Task。

Showcase 回归：[浅色桌面](showcase/light-desktop.png)、[深色桌面](showcase/dark-desktop.png)、[移动浅色](showcase/light-mobile.png)、[移动深色](showcase/dark-mobile.png)、[阅读](showcase/markdown-reading.png)、[编辑](showcase/richtext-editor.png)、[Modal](showcase/modal-open.png)、[Toast](showcase/toast-top-center.png)。

代码控件展开：[Dracula 桌面](showcase/controls-dracula-1440.png)、[GitHub Light 桌面](showcase/controls-github-light-1440.png)、[Dracula 移动](showcase/controls-dracula-390.png)、[GitHub Light 移动](showcase/controls-github-light-390.png)。

[完整报告与实际测试条件](../../site-visual-stabilization-20261011.md)；[状态页自动化结果](status-pages.json)。
