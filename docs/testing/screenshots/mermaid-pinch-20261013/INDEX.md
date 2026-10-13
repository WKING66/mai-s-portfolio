# Mermaid 触屏缩放截图

来自隔离浏览器的模拟视口，不是 Mate60 Pro 或 iPad 真机截图。Showcase 路径 `/design-showcase#mermaid-reading`，DPR 1、高度 900 CSS px。Edge 双指使用原生 CDP 多点触控；WebKit 使用 DOM 事件处理路径回归，不代表其原生触摸仲裁已实机通过。

|浏览器|CSS视口|方向|主题|正文双指放大|全屏双指放大|源码和控件|
|---|---|---|---|---|---|---|
|edge 153.0.4234.48|1280×900|Landscape|light|桌面仅滚轮回归|[全屏](edge-light-1280-fullscreen-fit.png)|[源码](edge-light-1280-source-above.png)|
|edge 153.0.4234.48|1280×900|Landscape|dark|桌面仅滚轮回归|[全屏](edge-dark-1280-fullscreen-fit.png)|[源码](edge-dark-1280-source-above.png)|
|edge 153.0.4234.48|810×900|Portrait|light|[正文](edge-light-810-inline-pinch.png)|[全屏](edge-light-810-fullscreen-pinch.png)|[源码](edge-light-810-source-above.png)|
|edge 153.0.4234.48|810×900|Portrait|dark|[正文](edge-dark-810-inline-pinch.png)|[全屏](edge-dark-810-fullscreen-pinch.png)|[源码](edge-dark-810-source-above.png)|
|edge 153.0.4234.48|390×900|Portrait|light|[正文](edge-light-390-inline-pinch.png)|[全屏](edge-light-390-fullscreen-pinch.png)|[源码](edge-light-390-source-above.png)|
|edge 153.0.4234.48|390×900|Portrait|dark|[正文](edge-dark-390-inline-pinch.png)|[全屏](edge-dark-390-fullscreen-pinch.png)|[源码](edge-dark-390-source-above.png)|
|edge 153.0.4234.48|320×900|Portrait|light|[正文](edge-light-320-inline-pinch.png)|[全屏](edge-light-320-fullscreen-pinch.png)|[源码](edge-light-320-source-above.png)|
|edge 153.0.4234.48|320×900|Portrait|dark|[正文](edge-dark-320-inline-pinch.png)|[全屏](edge-dark-320-fullscreen-pinch.png)|[源码](edge-dark-320-source-above.png)|
|webkit 18.5|810×900|Portrait|light|[正文](webkit-light-810-inline-pinch.png)|[全屏](webkit-light-810-fullscreen-pinch.png)|[源码](webkit-light-810-source-above.png)|
|webkit 18.5|810×900|Portrait|dark|[正文](webkit-dark-810-inline-pinch.png)|[全屏](webkit-dark-810-fullscreen-pinch.png)|[源码](webkit-dark-810-source-above.png)|
|webkit 18.5|1080×900|Landscape|light|[正文](webkit-light-1080-inline-pinch.png)|[全屏](webkit-light-1080-fullscreen-pinch.png)|[源码](webkit-light-1080-source-above.png)|
|webkit 18.5|1080×900|Landscape|dark|[正文](webkit-dark-1080-inline-pinch.png)|[全屏](webkit-dark-1080-fullscreen-pinch.png)|[源码](webkit-dark-1080-source-above.png)|
|webkit 18.5|390×900|Portrait|light|[正文](webkit-light-390-inline-pinch.png)|[全屏](webkit-light-390-fullscreen-pinch.png)|[源码](webkit-light-390-source-above.png)|
|webkit 18.5|390×900|Portrait|dark|[正文](webkit-dark-390-inline-pinch.png)|[全屏](webkit-dark-390-fullscreen-pinch.png)|[源码](webkit-dark-390-source-above.png)|

实际公开文章：/blog/blog-import-ui-qa-20261009-17%3A10，Edge 153.0.4234.48，390×844 CSS px、DPR 1、Portrait、light，移动视口/触摸模拟。

[移除尺寸控件](business/published-controls.png) · [正文双指放大](business/published-inline-pinch.png) · [全屏双指放大](business/published-fullscreen-pinch.png)

[Edge 元信息](edge-results.json) · [WebKit 元信息](webkit-results.json) · [公开文章元信息](business/edge-results.json)
