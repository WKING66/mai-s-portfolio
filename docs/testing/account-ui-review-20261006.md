# 个人中心与页面切换前端修复验收

日期：2026-10-06。继续修复待审核的 `feature/account-center-ui`，父分支为 `feature/visitor-registration`（起点 `20d50d4`）；本次改动基于该功能分支已有提交 `6099d4a`。不合并父分支或 main，不使用 ponytail。

## 本次范围与处理

1. 个人中心采用现有 Nuxt UI 的 `UTabs`：个人信息、密码修改分别展示，不再叠放。切换离开密码页时清空三个密码字段；写入期间禁用切换。
2. 统一页头移到应用根组件，删除各页面重复挂载。个人中心和管理页使用同宽容器；滚动条预留空间，账户入口尺寸固定，菜单不锁页面滚动条。
3. 项目输入框/文本框使用较小圆角。展示时间改为“项目完成日期（可选）”日期控件，使用浏览器日历和 ISO 日期，不做时区转换。未选择新日期时保留已有自由文本时间标签，不能因修改其他字段悄悄丢失旧数据。
4. 主题在 head 脚本中先于样式、水合、会话请求恢复；非敏感主题 Cookie 支持后续 SSR，兼容之前 localStorage 中的明确偏好，无明确偏好时跟随系统。认证检查不能再决定何时切换主题。
5. 初次读取资料、项目列表、项目编辑及公开资料时显示有尺寸的骨架，而非单行加载提示。已有本人资料可直接展示；仍保留受保护路由的最新会话校验，不以缓存角色绕过权限检查。

事实：本轮没有改后端代码、依赖、接口、数据库迁移、认证协议或权限实现。维护者已有的 `GlobalExceptionHandler.java`、`application-dev.yaml`、`AuthServiceImpl.java` 修改未带入提交。本轮不宣称已经优化后端响应时间，后端检查待前端审核后进行。

## 环境与持久化副作用

- 前端最终生产构建预览：`http://127.0.0.1:3004`；沿用专用后端 `http://127.0.0.1:19334`，本轮不重建该后端。
- 沿用专用 PostgreSQL `portfolio_dev` / `project_acceptance_20261005` schema 及 Redis 前缀 `mai-portfolio:account-ui-20261006`，未修改 `public` 数据或本地 Docker。
- 测试账号：普通账号 `account_ui_20261006_01`、站长 `project_owner`；密码来自被忽略的验收文件，不写入本文、截图或提交。
- 本次没有上传头像或修改密码。唯一项目写入为专用草稿 ID=15 的完成日期，版本从 3 更新到 4；原四个公开验收项目不变。
- 浏览器主题选择写入非敏感 Cookie/localStorage；登录、注销使用原有认证接口并产生正常会话变化。

## 真实浏览器操作与结果

Cookie、CSRF、密码及密码密文均省略。下表是实际操作记录，不将单元测试模拟等同于浏览器验证。

|页面 / 操作|输入或请求|实际结果|
|---|---|---|
|普通账号进入 `/account`|沿用有效登录会话，读取本人资料|“个人信息”选中；仅一个可见标签面板；昵称字段存在，密码字段不在当前页面 DOM 中|
|点击“密码修改”|无写请求|仅密码面板显示，昵称字段不显示；三个密码输入可用|
|当前密码填写测试假值，然后切换“个人信息”再切回“密码修改”|`not-a-real-password`，不点击“更新密码”|当前密码字段已清空；未发送修改密码请求；切换前后页头左边位置均为 343.5px|
|普通账号菜单点击“退出登录”，站长在 `/login` 填写账号密码并点击“登录”|DELETE、POST `/api/v1/auth/session`，敏感值省略|退出回首页；站长登录成功后进入项目管理，沿用服务端 OWNER 角色与统一 CSRF|
|站长项目管理页打开账户菜单|点击 `[data-account-entry]`|页头左边位置 343.5px、宽 1200px 均不变；页面 `body` overflow 为 visible，未锁住滚动条|
|菜单点击“个人中心”，再从菜单点击“管理项目”|`/admin/projects` → `/account` → `/admin/projects`|页头 DOM 实例保持同一个；左边位置 343.5px、宽 1200px 均不变；没有卸载再恢复账户入口|
|管理导航点击“站长公开资料”|`/admin/projects` → `/admin/profile`|页头仍为同一实例，位置/宽度不变；只存在一个 `.site-header`|
|站长进入 `/admin/projects/15`|GET `/api/v1/admin/projects/15`|日期输入实际 `type=date`，标签“项目完成日期（可选）”；标题与摘要控件的实际圆角为 6px|
|完成日期输入 `2026-10-06`，点击“保存项目”|PATCH `/api/v1/admin/projects/15`，`version=3, timeLabel=2026-10-06`，其他字段沿用原值|200 OK；返回 ID=15、version=4、timeLabel=2026-10-06；项目仍是草稿；日期无时区偏移|
|设置深色主题并整页刷新|主题 Cookie 为原始枚举 `dark`|响应 HTML 根节点包含 `data-theme=dark` 和 `.dark`；页面背景 `rgb(7,16,24)`；主题脚本位于样式表之前|
|在真实页面执行实际首屏脚本，给其 localStorage 参数注入读取异常|已有深色主题 Cookie；不修改全局存储实现|仍恢复 dark、深色背景、规范 Cookie；头部只有一个。该步骤验证的是脚本存储失败分支，不是操作系统级禁用浏览器存储|
|深色状态退出登录后刷新首页|DELETE `/api/v1/auth/session`；刷新 `/`|仍为 dark / `rgb(7,16,24)`；账户入口显示“登录”，主题不受会话结果影响|
|匿名分别访问 `/login`、`/register`|桌面浏览器视口高度 984px|两页主题均为 dark；文档高度均为 984px，无横向溢出、无多余纵向滚动条；页头数均为 1|

深色个人信息页面已截图并视觉检查，截图保留在被忽略的本地验收目录，不纳入仓库。日期值通过真实浏览器日期输入及保存验证；未声称逐一验证所有浏览器原生日历弹窗的外观。

## 测试与构建

- 前端 Vitest：13 个测试文件、**170/170 通过**。
- 新增主题脚本测试 12 项：首屏恢复、Cookie 优先、历史存储迁移、系统默认、存储不可读、HTTPS Secure 与枚举校验。
- 新增日期校验测试 16 项：合法 ISO、闰年、世纪规则、边界年份、非法日期及旧展示标签。
- 增加编辑回归：修改其他项目字段时，原 `2024 – 2025` 时间标签仍原样提交。
- Nuxt typecheck 通过；最终生产 build 通过。真实浏览器运行的是该生产构建，不只是开发服务器热更新。
- 既有认证拦截器、权限、账号资料、注册和项目交互测试一并执行。本次没有重新运行后端测试，以免混淆“前端修复”与维护者尚待审查的后端改动。

## 核心文件

- 主题：`frontend/app/utils/siteTheme.ts`、`frontend/app/composables/useSiteTheme.ts`、`frontend/nuxt.config.ts`、`frontend/app/app.vue`、`frontend/app/components/SiteHeader.vue`。
- 布局/加载：`frontend/app/components/AccountMenu.vue`、`AdminShell.vue`、`AdminProjectList.vue`、`ProfileEditor.vue`、`frontend/app/pages/account.vue` 及去重页头的公开/登录/注册页、`frontend/app/assets/css/main.css`。
- 表单：`frontend/app/components/AccountSettings.vue`、`ProjectEditor.vue`、`frontend/app/app.config.ts`、对应账号/管理常量、`frontend/app/utils/projectDate.ts`。
- 测试：`frontend/app/test/siteTheme.test.ts`、`projectDate.test.ts`、`adminProjectInteractions.test.ts`。

## 限制与待审核事项

- 预留骨架和常驻布局改善等待期间的显示，不会让真实网络/后端耗时消失；没有执行慢网压力或后端耗时优化。
- 首屏内联主题脚本需能执行；如部署将来启用严格 CSP，需要对应 nonce/hash 放行，不能直接禁用它后仍声称无闪屏。
- 菜单与标签切换、桌面尺寸已实际验证；实体手机、屏幕阅读器、所有浏览器与所有网络失败场景未全量验证。
- 旧时间自由文本保留是兼容现有业务的选择，本轮不迁移数据字段。日期控件的原生外观随浏览器/系统变化。
- 未继续重构后端，也未继续博客、Agent 或上传功能。提交后等待维护者审核。
