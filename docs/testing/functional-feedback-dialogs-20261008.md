# 函数式提示弹窗验收

> 历史记录：维护者随后明确“仅提示、无需确认、显示/关闭不抖动、复用 Nuxt UI、上方居中”。本方案已被原生 Toast 替换，`FeedbackDialog.vue` 与自建模态消息管理已删除。当前设计与验收见 [Nuxt UI Toast 提示修正](nuxt-ui-toast-feedback-20261008.md)。以下保留当时实现与验收事实，不代表当前实现。

日期：2026-10-08。功能分支 `feature/functional-feedback-dialogs`，父分支 `feature/api-feedback-alerts`，起点 `1b22025`。不合并 main、不推送。

## 问题与业务规则

之前只是把提示段落替换为页面里的 UAlert：每个页面维护错误/成功文本，并在指定位置渲染。它仍是页面内容，没有实现维护者要求的弹出提示。

稳定规则：操作成功和失败需要显著、可关闭的结果提示；失败不清除未保存输入、不自动重复写入；权限、业务校验和 API 生命周期由原能力负责。加载中、空列表、字段说明与未保存状态持续存在于对应内容位置，不冒充已经完成的操作结果。

已明确的变化点：多个请求同时失败、路由切换后提示仍需可见、关闭后相同失败再次发生、SSR 查询错误在浏览器挂载后展示、切换账号后的旧响应不能误报。它们统一由展示边界处理，不能为每个页面复制弹窗管理。

## 功能正确性

- `useFeedback()` 在 setup 取得函数入口，在事件/异步操作中调用，页面不再写成功/失败提示标签。
- 使用现有 Nuxt UI `useOverlay` 和 `UModal`，由 UApp 现有 OverlayProvider 挂载到 Portal。居中弹出、有遮罩，支持“知道了”、关闭按钮、Escape 和点击外部关闭。
- 一个 Nuxt 应用共用一套弹窗。多个同时到达的结果集中展示，相同类型/文字去重；关闭后的同样错误重新弹出。上一批消息不会回放。
- 提示跨页面跳转保留；调用组件无需传递提示状态到下一页。
- 公开 SSR 的读取错误仅在客户端挂载后展示，继续响应后续读取失败，并使用固定业务提示，不展示原始 FetchError 地址。
- 账号资料加载在共享请求能力中提示一次，菜单和个人中心不重复提示。旧账号响应的 `AUTH_SESSION_CHANGED` 不作为当前账号失败弹出。
- 消息以 Vue 文本插值展示，不使用 v-html。API 拒绝后保留用户输入，只有用户明确重试才再次提交。

调用示例：

```ts
const feedback = useFeedback()
feedback.success('昵称已保存。')
feedback.error(cause, '保存失败，请重试。')
feedback.info('请查看操作说明。')
```

## 设计与职责边界

| 职责 | 承载位置 |
|---|---|
| 提示类型、图标与颜色 | `constants/feedback.ts` |
| 弹窗内容及关闭交互 | `components/FeedbackDialog.vue` |
| 函数入口、应用隔离、同时消息归并 | `composables/useFeedback.ts` |
| SSR 读取错误的挂载后展示 | 同文件的 `useReadErrorFeedback` |
| 何时成功/失败、状态更新和重试 | 现有页面与对应 UI composable |
| API 业务错误、认证与 CSRF | 原 API/认证能力，未另建拦截器 |

接入登录、注册、密码修改结果、个人中心昵称/头像/密码、账号菜单注销、会话恢复、项目管理列表与编辑、站长资料编辑、公开首页/项目查询及无权限页面。

删除了各页面/项目 composable 的纯展示 error/notice 状态、Pinia profileError 和无引用菜单错误常量；账号资料仅保留实际资料/pending/请求世代。持续加载使用原按钮 loading/骨架；AdminShell 的鉴权占位改为骨架。资料编辑的能力说明保留为普通说明文字。

## 当前支持的扩展点

- 新增操作使用现有 success/error/info 函数，不新增页面标签、弹窗实例或提示状态。
- 新增公开读取错误沿用 `useReadErrorFeedback`，不在 SSR 时打开弹窗。
- 更换提示布局集中修改 FeedbackDialog；更换 UI 弹窗依赖只影响组件与 useFeedback，业务调用保持不变。

## 主动没有提前设计的未来扩展

没有加入通知队列框架、全局事件总线、Registry、可持久化通知、自动重试、弹窗内任意业务表单或通用确认工作流。既有丢弃未保存修改的确认行为不属于本次结果提示改造，仍沿用原逻辑。

## Maintainability Review

- 同类操作不再复制 Controller/业务逻辑或页面提示块；只调用统一展示函数。
- 唯一展示实现为 FeedbackDialog；业务模型/授权事实不迁入弹窗组件。
- SSR 应用用 WeakMap 隔离，不用进程级消息数组。页面组件卸载不清理已产生的操作结果。
- 没有为固定测试文本、用户名或路由写生产分支。去重按类型与文字，不按页面分类。
- 失败路径覆盖重复错误、并发消息、SSR 错误、关闭后重试、跨路由提示、旧账号响应、未知异常和表单输入保留。
- 复用既有 ApiRequestError/错误码、业务提示常量与 UI 依赖，没有新增后端配置或敏感日志。
- 原 `feedbackAlerts.test.ts` 只数页面标签，已删除；改用函数生命周期测试和真实浏览器行为验证。测试数量减少不被当作质量退步或设计目标。
- 没有遗留新旧两套运行中的成功/失败 UAlert。维护者原来在 AdminShell 加入的注释仍保留为其本地修改，不纳入提交。

## 测试结果与技术债务

- Node 24 下 Vitest：20 个文件、228 项全部通过。
- Nuxt typecheck：通过。
- Nuxt 生产构建：通过。
- 隔离 Nuxt 构建与 Chromium：7 组全部通过。包括弹窗 Portal、写入失败保留输入、重复同一失败、成功后恢复、消息文本转义、Escape、SSR 失败后重试、新项目保存跨路由、权限/401 恢复，以及上一轮持久化/锚点回归。无水合警告、无未处理页面异常。
- 浏览器使用模拟后端，未写入真实账号、数据库或 OSS。尚未实测 Firefox/Safari；UI 焦点与关闭行为沿用 Nuxt UI/Reka。
- 构建仍有现有第三方包的 exports 弃用警告，不影响构建完成，本轮没有改依赖版本。

复测命令见 `frontend-persistent-state-anchor-20261008.md` 的浏览器运行说明；同一集成脚本现已扩充为 7 组。

本轮保留维护者的 application-dev.yaml、AuthController.java、registration.ts、AdminShell.vue 注释和 docs/handoffs 修改。AdminShell 仅精确提交本轮占位改造。
