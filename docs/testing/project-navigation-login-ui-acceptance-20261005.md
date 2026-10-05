# 项目导航与登录页修正：实际验收记录

日期：2026-10-05。分支：`feature/project-navigation-login-ui`。
父分支：`feature/project-management-display`，基线提交 `81ee778`；本轮未合并父分支/main。

## 修正范围

- 顶部及手机菜单的“项目”改为 `/#projects`，不是 `/projects`。
- 首页最多四张卡片，二列布局；已发布总数超过四个时，项目标题区右上角显示“更多项目”，只有此入口进入 `/projects` 项目栏目。栏目页及分页能力保留。
- 公共导航增加 `/login` 入口：匿名显示“登录”，已登录显示“账户”。手机端入口同样可见；站长管理入口在手机菜单中保留。
- 登录页使用公共导航、主题变量、Tailwind、A 版 SurfaceCard/光效和响应式两栏布局。登录页自行恢复会话，导航不重复查询。保留用户名/密码自动填充、错误提示、密码提交后清空、登录跳转、已登录状态和注销。
- 仅修改前端表现；没有新增注册、找回密码、接口或认证协议。RSA/Cookie/CSRF/角色授权与后端保持不变。

## 真实浏览器操作与结果

环境：前端 `http://127.0.0.1:3004`，后端 `http://127.0.0.1:19334`，仍使用上一轮隔离验收 schema；非正式内容。

|步骤|页面/点击/输入|请求及结果|实际页面结果|
|---|---|---|---|
|1|首页点击导航“项目”|GET 首页公开项目，PUBLIC/page=1/size=4，200|URL=`/#projects`，项目区顶部距离视口约 69.53px，未被导航遮挡；4 张卡片；更多入口不存在|
|2|仅将已有验收夹具项目 id=5 临时发布（浏览器同源 API，不是新增页面功能）|POST /api/v1/admin/projects/5/publish，当前 version、CSRF 脱敏 → 200/PUBLISHED|公开总数变为 5|
|3|刷新首页项目区|GET /api/v1/projects?view=PUBLIC&page=1&size=4 → 200|仍仅 4 张卡片；标题区右侧出现“更多项目”，href=/projects，右边界 1523.5px、标题左边界 363.5px|
|4|点击右上角“更多项目”|GET /api/v1/projects?view=PUBLIC&page=1&size=12 → 200|进入 /projects，栏目展示 5 张卡片|
|5|栏目页点击导航“项目”|GET 首页公开项目 → 200|返回 /#projects，项目区顶部仍约 69.53px，4 张卡片|
|6|恢复夹具 id=5 草稿（同源 API）|POST /api/v1/admin/projects/5/unpublish，当前 version → 200/DRAFT|最终公开总数恢复 4，没有删除验收数据或对象文件|
|7|点击顶部“账户”|进入 /login；GET /api/v1/auth/session → 200|显示当前验收账号 project_owner、“管理项目”“返回作品集”“注销账号”|
|8|点击“注销账号”|DELETE /api/v1/auth/session → 200|登录表单出现，顶部入口变为“登录”，管理入口消失|
|9|用户名 project_owner；输入错误验收密码；点击“登录”|POST /api/v1/auth/session → 401|提示“用户名或密码错误”；密码输入清空；按钮恢复可用；留在 /login|
|10|保持用户名，输入隔离夹具正确密码；点击“登录”|POST /api/v1/auth/session → 200；GET 会话 → 200|进入 /admin/projects，标题“项目管理”，站长导航与账号信息正常|
|11|管理导航点击“注销”|DELETE /api/v1/auth/session → 200；登录页 GET 会话 → 200|回到 /login；标题“登录 · 阿霾作品集”；密码为空、登录表单正常|

密码、Cookie、CSRF 和 RSA 密文均未写入记录。测试使用的账号与凭据来自被忽略的隔离夹具文件，未修改正式账号。

## 验证与边界

- 前端 typecheck 成功、Vitest 22/22 通过、Nuxt 生产构建成功；加载最终构建后完成上述浏览器操作。
- 实际保存并查看了深/浅主题登录页截图：标题、输入框、按钮、公共导航和页面配色均正常，无遮挡。截图只保存在忽略目录，未提交。
- 后端本轮未修改，也未重新跑 Maven 全量测试；登录、注销和公开项目读取均连接实际后端验证。
- 保留上一轮验收记录，不将其“三卡片/超过三个”历史结果改写成本轮新规则；当前规则由本文件覆盖。
- 手机入口与响应式布局已经实现，但没有将桌面浏览器验收宣称为真实手机设备验收。
- 保留维护者 `application-dev.yaml` 的既有修改，未将其、环境文件、日志、截图或构建产物提交。

核心文件：SiteHeader.vue、SectionHeading.vue、pages/index.vue、pages/login.vue、constants/projects.ts、constants/messages.ts。
