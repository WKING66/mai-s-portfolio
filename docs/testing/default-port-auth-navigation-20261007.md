# 默认开发端口：认证与导航修复验收

日期：2026-10-07。功能分支：`feature/account-center-ui`；父分支：`feature/visitor-registration`（起点 `20d50d4`）。这是同一待审功能的修复，不合并父分支/main。不使用 ponytail。

## 环境与此前验收的局限

- 本轮浏览器只访问 `http://127.0.0.1:3000`，API 经前端代理访问维护者在 IDEA 启动的 `9333` dev 后端。
- 前端使用原有 `frontend/.env`，没有指定其他后端、Redis 前缀、数据库/schema 或验收账号。根目录 `.env` 当前不存在；没有重新创建或以测试配置替代。
- 前一轮 `3004 → 19334` 使用独立验收账号 `project_owner` 和专用 schema。那份记录不能证明维护者默认环境可用。本轮关闭了已确认由本 Agent 启动的 3004 预览，不停止维护者的 9333 后端。
- 维护者提供的真实账号凭据只从 Git 忽略的 `.local-backups/default-login.env` 读取，输入默认登录页。不打印密码/密文/请求体/Cookie/CSRF，不修改或重置密码，不生成或替换密钥。
- 本地仅校验了公钥格式及与 dev 默认私钥文件的对应关系，结果为匹配；真正的运行时验证以默认页面登录返回 200 为准，不能仅凭文件匹配认定运行进程配置正确。

## 根因、改动与边界

1. 修复前默认 Nuxt dev 日志出现 `ReferenceError: Cannot access '_routeRulesMatcher' before initialization`。浏览器只有 SSR HTML，没有完成水合；账户入口保持“正在恢复账户”，未捕获到会话请求，栏目没有 `aria-current`。
2. `app/router.options.ts` 通过 `#app` 聚合入口导入 `useNuxtApp`，反向加载布局与路由规则，引起启动依赖循环。改为 `#app/nuxt` 狭窄入口，与当前 Nuxt 自身的路由选项实现一致。没有改第三方源码，也没有通过禁用 SSR 或删除认证绕过错误。
3. 复查发现初始滚动一律返回 `false` 会让带锚点的刷新留在顶部。改为优先即时恢复传入的历史位置；没有历史位置时即时定位锚点。无锚点/无历史位置不额外滚动。站内锚点继续遵循平滑/减少动效设置，不等待会话或页面请求。
4. 保留 Cookie、RSA-OAEP、CSRF、服务端角色校验、Pinia 共享权限和管理缓存。没有新增会话轮询，没有改后端、数据库、账户资料或项目数据。

依据：本地 Nuxt 4.5.2 `dist/pages/runtime/router.options.js` 使用 `#app/nuxt`；官方 [Nuxt 路由实现](https://github.com/nuxt/nuxt/blob/main/packages/nuxt/src/pages/runtime/plugins/router.ts) 也区分首次滚动与后续导航。开发环境浏览器复现是本次修复的主要证据，不以生产构建通过代替。

## 真实浏览器操作记录

工具：browser-act，本轮独立会话 `default-port-20261007`。所有页面均为默认 3000；以下只记录非敏感状态。浏览器快照中的数字索引只属于当时快照，不作为未来可复用定位器。

| 步骤 | 页面/动作与输入 | 请求与结果 | 页面结果 |
| --- | --- | --- | --- |
| 1 | 启动原配置前端，维护者启动原 dev 后端；分别读取 3000 代理和 9333 的匿名会话 | `GET /api/v1/auth/session`：两者 HTTP 200，`code=OK`，`loggedIn=false` | 确认默认代理连通，不依赖其他端口 |
| 2 | 修复前刷新首页 | 页面 HTML 正常，但客户端启动报上述初始化错误；未捕获会话请求 | `hydrated=false`，账户按钮禁用，栏目没有高亮 |
| 3 | 修正导入后刷新首页 | 浏览器 `GET /api/v1/auth/session`：200 | `hydrated=true`；账户按钮显示“登录” |
| 4 | 首页依次点击页头“联系”“项目” | 不提交业务写入 | URL 分别为 `/#contact`、`/#projects`；对应且仅对应栏目有 `aria-current=location`；项目位置约 `scrollY=1353` |
| 5 | 在 `/#projects` 用浏览器原生 `location.reload()` 刷新 | 首次启动恢复会话；锚点处理不依赖该接口完成 | 修复前 `scrollY=0`、错误高亮“关于我”；修复后 `scrollY=1353`、高亮“项目” |
| 6 | `/login`：用户名 `owner`，密码从维护者提供的忽略文件输入，点击“登录” | `POST /api/v1/auth/session`：200。未输出密码、RSA 密文、Cookie 或 CSRF | 导航到 `/admin/projects`；账户菜单显示 `owner`；管理列表显示维护者已有项目 |
| 7 | 打开账户下拉框，点击“个人中心” | 没有新增 `GET /api/v1/auth/session` | 到 `/account`；默认“个人信息”展示昵称/头像，菜单保留管理权限 |
| 8 | 个人中心点击“密码修改” | 不提交密码修改 | “密码修改”激活，3 个密码输入框；昵称输入框不在当前 DOM 中 |
| 9 | 账户菜单点“管理项目”，再点击后台“站长公开资料”及“项目管理”，然后页头点“博客” | 此序列没有新增会话查询；只读取相应业务资料 | 返回 `/#blog`、高亮“博客”，账户仍为 `owner`，管理入口不因切换消失 |
| 10 | 完成构建后在原 3000 重启 dev 前端，再原生刷新 `/#blog` | `GET /api/v1/auth/session`：200，恢复已有 Cookie 会话 | `hydrated=true`、账户仍为 `owner`、高亮“博客”，`scrollY=2058`；没有回到顶部等待会话再平滑跳转 |
| 11 | 打开账户菜单，点击“退出登录” | `DELETE /api/v1/auth/session`：200；保留原有 CSRF 处理 | 返回 `/`，账户入口“登录”、高亮“关于我”；关闭本轮浏览器会话 |

登录/注销会按原有实现写入或清理 Redis 会话、消耗一次登录限流额度，并产生脱敏审计日志。这是本轮真实联调的认证副作用；没有清空 Redis、重置限流或修改账号数据库记录。

## 自动检查及范围

> 后续更正：本文第 10 步只核对了水合后的位置，未证明首绘过程中没有跳顶。切换锚点后的首次刷新仍能复现，已在独立分支修复；原因与首绘时序验收见 [锚点首次刷新跳顶修复](anchor-refresh-scroll-20261007.md)。本轮历史记录中的初始化滚动策略不再作为最终实现依据。

- 前端 17 个测试文件、203 个用例通过。
- 最终前端类型检查通过。
- 原有 `.env` 下生产构建通过，产物约 4.79 MB；只做构建检查，没有另开生产预览端口。构建后恢复默认 3000 dev 服务。
- 新增路由启动入口回归测试：禁止聚合导入，验证同页锚点、首次锚点和同页查询不依赖会话/页面完成事件。它模拟 Nuxt 运行时，不能替代真实 dev 浏览器启动检查。
- 现有滚动测试更新为覆盖首次历史位置、即时片段定位、减少动效和其他页面的顶部定位。
- 本轮没有执行头像上传、密码修改、项目编辑/发布/下架等业务写入，也没有验证普通用户注册或停用/撤销角色的完整后台变更链路。这些不应被本次默认环境登录验证隐含宣称为通过。

## 核心文件与本地配置保护

- `frontend/app/router.options.ts`
- `frontend/app/utils/scrollPosition.ts`
- `frontend/app/test/routerOptions.test.ts`
- `frontend/app/test/scrollPosition.test.ts`
- 本文。

维护者已有 `GlobalExceptionHandler.java`、`application-dev.yaml`、`AuthServiceImpl.java` 修改保持原样、排除本轮提交。所有 `.env`、凭据文件、运行缓存、构建产物和日志不提交。
