# 个人资料管理前后端联调记录

日期：2026-10-05。功能分支：`feature/profile-management`；父分支：`feature/frontend-auth-interceptors`，起点 `22601ea`。未合并父分支或 main。

## 范围与环境

- 为既有 GET/PATCH `/api/v1/admin/profile` 补齐资料编辑页面、导航、统一鉴权请求及公开缓存失效；未新增后端协议或上传/媒体绑定功能。
- 前端真实生产预览 `http://127.0.0.1:3004`，后端 `http://127.0.0.1:19334`；数据库为独立 `portfolio_dev.project_acceptance_20261005` schema，账号 `project_owner` 与 `project_normal`。
- 浏览器独立会话 `profile-management-20261005`；密码只从被忽略的本机夹具文件读取。以下不记录密码、RSA 密文、Cookie、CSRF 值。
- 以下是已执行操作的记录，不是可直接运行的自动化脚本。未改正式账号、正式资料、OSS 或 Docker。

## 页面操作、接口与结果

|步骤|页面及操作|输入/请求|真实结果|
|---|---|---|---|
|匿名访问|地址栏输入 `/admin/profile`|GET `/api/v1/auth/session`|200，匿名；跳到 `/login?returnTo=/admin/profile`，编辑器不挂载，不请求管理资料|
|站长登录|登录页填用户名与夹具密码，点击“登录”|用户名 `project_owner`；POST session，随后 GET session|均 200；确认 OWNER，返回资料页；GET admin/profile 200|
|保存文字|资料页填昵称、定位、介绍，点击“保存资料”|昵称 `阿霾 · 资料页验收`；定位 `Java 后端与 AI 智能体开发 · 联调`；介绍见下；PATCH admin/profile，原样回传 updatedAt|200/OK；返回新快照与 `2026-10-05T11:58:52.724`；提示“个人资料已保存，公开首页已更新。”，状态变为已保存|
|公开同步|点击“查看公开首页”|公开资料 GET；同时读取首页原始 HTML|h1 与介绍显示新值，SSR HTML 也包含新值；仍为四张公开项目卡片|
|非法邮箱|返回资料页，将 Email 填为 `invalid-email`，点击保存|PATCH admin/profile|422/VALIDATION_FAILED，“请求参数不合法”；非法输入保留，无成功提示|
|取消联系方式|GitHub、Email 均填单个空格，点击保存|服务端请求中均为 null|PATCH 200；随后公开 profile GET 200，githubUrl/email 均 null；表单回填为空|
|并发冲突|同源 API 辅助模拟另一编辑者保存，然后当前页面改介绍并点击保存|辅助 PATCH 将定位设为 `并发联调写入`，200；当前介绍 `这个未保存输入应在版本冲突后保留。`，携带旧 updatedAt|当前 PATCH 409/DATA_CONFLICT；“数据状态已变化，请刷新后重试”；未保存输入保留，无自动重试|
|取消重新读取|当前表单有未保存输入，点击“重新读取”|浏览器临时模拟 confirm 返回 false|输入保留，没有增加 GET 请求；未单独验证原生对话框视觉|
|确认重新读取|再次点击“重新读取”|浏览器临时模拟 confirm 返回 true|GET 200；最新定位 `并发联调写入` 回填，错误清空|
|CSRF 错误|将定位改回上述联调定位，只对下一次 PATCH 注入无效 CSRF 头，然后点击保存|PATCH admin/profile|403/CSRF_INVALID；统一层刷新会话，仍有 OWNER；编辑器和输入保留，提示“安全令牌已更新，请重新提交本次操作。”|
|显式重试|点击“保存资料”|正确 CSRF，手动第二次 PATCH|200；updatedAt `2026-10-05T12:04:15.556`；不曾自动重放失败写请求|
|正常注销|点击管理导航“注销”|DELETE session|200；清理权限并回到登录页|
|普通账号|登录页输入 `project_normal` 与夹具密码，点击登录；直接访问资料 URL|POST/GET session 200；路由守卫查询真实角色|转到 `/forbidden`，不挂载资料编辑器；同源手工 GET admin/profile 返回 403/FORBIDDEN|
|管理 SSR|重新登录站长，读取 `/admin/profile` 原始 HTML|携带有效站长 Cookie 的实际 HTTP 读取|无 `profile-name` 编辑表单，无“资料页验收”管理快照；存在 noindex,nofollow|
|服务端会话过期|通过同源辅助 DELETE session，但保留页面旧权限状态；介绍填 `Expired session must not persist in profile`，点击保存|DELETE 200；下一次实际 PATCH admin/profile|401/UNAUTHENTICATED；转 `/login?returnTo=%2Fadmin%2Fprofile`，编辑器卸载，登录框存在；该介绍没有保存|

成功保存的介绍：`资料管理联调：关注工具权限、token 预算与可验证的性能改善。本内容仅在隔离验收库中使用。`

辅助请求、临时 confirm 结果及无效 CSRF 注入用于负向联调，不是产品新增功能；完整导航后浏览器临时函数已消失。最终注销，独立浏览器会话关闭。验收资料保留在隔离库供审核，未声称恢复原始夹具文字。

## 实现与检查

- 管理页面声明 OWNER 元数据，父 Auth 确认权限后才挂载编辑器；读取在 onMounted 发起。API 复用现有统一请求拦截器，不在组件判断用户名。
- 原样回传服务端时间戳，不经 Date 转换；失败不覆盖表单；成功只清理 `profile:PUBLIC`，不把管理快照写入公开缓存。
- `/admin/profile` 精确加入安全登录回跳名单；含查询参数、外域与未知管理路径仍不放行。
- Vitest **48/48** 通过，6 个测试文件；新增资料 API 4 个用例及回跳正/负用例。维护者迁移的五个测试文件及其导入修改纳入提交。
- Nuxt 类型检查、最终生产构建和 git diff --check 通过。构建保留既有第三方 `@vue/shared` 弃用警告。
- 最终预览正确加载前端环境并代理到 19334；早期错误代理预览不作为验收结果。未修改后端业务代码，因此未重复 Maven 全量检查。
- 独立只读静态复核覆盖七个资料实现文件及六个测试文件，未发现可确认的高、中严重度缺陷；这不等同于维护者批准或绝对无漏洞。

## 副作用与未验证边界

只更新隔离 site_config，创建/注销隔离会话，没有新增项目或操作文件存储。未验证公网 HTTPS、跨标签页即时同步、移动设备和资料修改期间的角色撤销专项链路；共通角色撤销/停用已在上一模块验收，新资料页通过相同 Auth 与请求拦截器并经过普通账号/401 检查。

维护者 application-dev.yaml 改动保留但不提交；不提交环境文件、凭据、截图、日志或构建产物。本模块提交与推送后继续已授权的首版剩余功能，不自动合并。
