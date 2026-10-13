# Mermaid 容器交互截图

全部来自隔离真实浏览器进程，视口/触摸为模拟；不是 iPad 或手机实机截图。每组路径 `/design-showcase#mermaid-reading`，高度 900 CSS px、DPR 1，宽度变化测试另有 320/390 CSS px。

|浏览器|CSS视口|方向|主题|容器尺寸调整|全屏适配|全屏拖拽|源码在上|
|---|---|---|---|---|---|---|---|
|edge 153.0.4234.48|1280×900|Landscape|light|[尺寸](edge-light-1280-inline-resized.png)|[适配](edge-light-1280-fullscreen-fit.png)|[拖拽](edge-light-1280-fullscreen-pan.png)|[源码](edge-light-1280-source-above.png)|
|edge 153.0.4234.48|1280×900|Landscape|dark|[尺寸](edge-dark-1280-inline-resized.png)|[适配](edge-dark-1280-fullscreen-fit.png)|[拖拽](edge-dark-1280-fullscreen-pan.png)|[源码](edge-dark-1280-source-above.png)|
|edge 153.0.4234.48|810×900|Portrait|light|[尺寸](edge-light-810-inline-resized.png)|[适配](edge-light-810-fullscreen-fit.png)|[拖拽](edge-light-810-fullscreen-pan.png)|[源码](edge-light-810-source-above.png)|
|edge 153.0.4234.48|810×900|Portrait|dark|[尺寸](edge-dark-810-inline-resized.png)|[适配](edge-dark-810-fullscreen-fit.png)|[拖拽](edge-dark-810-fullscreen-pan.png)|[源码](edge-dark-810-source-above.png)|
|edge 153.0.4234.48|390×900|Portrait|light|[尺寸](edge-light-390-inline-resized.png)|[适配](edge-light-390-fullscreen-fit.png)|[拖拽](edge-light-390-fullscreen-pan.png)|[源码](edge-light-390-source-above.png)|
|edge 153.0.4234.48|390×900|Portrait|dark|[尺寸](edge-dark-390-inline-resized.png)|[适配](edge-dark-390-fullscreen-fit.png)|[拖拽](edge-dark-390-fullscreen-pan.png)|[源码](edge-dark-390-source-above.png)|
|edge 153.0.4234.48|320×900|Portrait|light|[尺寸](edge-light-320-inline-resized.png)|[适配](edge-light-320-fullscreen-fit.png)|[拖拽](edge-light-320-fullscreen-pan.png)|[源码](edge-light-320-source-above.png)|
|edge 153.0.4234.48|320×900|Portrait|dark|[尺寸](edge-dark-320-inline-resized.png)|[适配](edge-dark-320-fullscreen-fit.png)|[拖拽](edge-dark-320-fullscreen-pan.png)|[源码](edge-dark-320-source-above.png)|
|webkit 18.5|810×900|Portrait|light|[尺寸](webkit-light-810-inline-resized.png)|[适配](webkit-light-810-fullscreen-fit.png)|[拖拽](webkit-light-810-fullscreen-pan.png)|[源码](webkit-light-810-source-above.png)|
|webkit 18.5|810×900|Portrait|dark|[尺寸](webkit-dark-810-inline-resized.png)|[适配](webkit-dark-810-fullscreen-fit.png)|[拖拽](webkit-dark-810-fullscreen-pan.png)|[源码](webkit-dark-810-source-above.png)|
|webkit 18.5|1080×900|Landscape|light|[尺寸](webkit-light-1080-inline-resized.png)|[适配](webkit-light-1080-fullscreen-fit.png)|[拖拽](webkit-light-1080-fullscreen-pan.png)|[源码](webkit-light-1080-source-above.png)|
|webkit 18.5|1080×900|Landscape|dark|[尺寸](webkit-dark-1080-inline-resized.png)|[适配](webkit-dark-1080-fullscreen-fit.png)|[拖拽](webkit-dark-1080-fullscreen-pan.png)|[源码](webkit-dark-1080-source-above.png)|
|webkit 18.5|390×900|Portrait|light|[尺寸](webkit-light-390-inline-resized.png)|[适配](webkit-light-390-fullscreen-fit.png)|[拖拽](webkit-light-390-fullscreen-pan.png)|[源码](webkit-light-390-source-above.png)|
|webkit 18.5|390×900|Portrait|dark|[尺寸](webkit-dark-390-inline-resized.png)|[适配](webkit-dark-390-fullscreen-fit.png)|[拖拽](webkit-dark-390-fullscreen-pan.png)|[源码](webkit-dark-390-source-above.png)|

详细测试记录：[Edge](edge-results.json)、[WebKit](webkit-results.json)。Edge 窄屏触摸拖拽和手柄由原生 CDP touch 事件验证；WebKit 为 Windows 上 18.5 内核模拟。

图表类型与安全回归：[元信息](safety/results.json)、[桌面宽图](safety/safe-wide-1280.png)、[手机宽图](safety/safe-wide-390.png)、[错误回退](safety/error-390.png)。其余类型截图位于 safety 子目录。
