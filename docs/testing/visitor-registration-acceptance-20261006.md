# 普通访客注册前后端联调记录

日期：2026-10-06。功能分支：`feature/visitor-registration`；父分支：`feature/portfolio-seo`，起点 `4827830`。本轮不合并父分支或 main。

## 范围、验收状态与环境

- 新增普通访客注册接口及 `/register` 页面；注册、普通登录和拒绝管理权限已完成真实浏览器联调。复用现有通用认证，不新增另一套会话协议。
- 对应 Spec **FR-045**、Task **T079**，完成 SC-022 中“普通账号可注册/登录、不能管理、匿名仍可阅读”的部分。**SC-022 的文章许可导出、T069 的完整导出鉴权链路尚未完成**，由后续博客模块验证，不能因注册完成而整体勾选。
- 最终限流方案为“复用组件，注册和登录分别计数”。**独立计数的最终双实例测试及真实浏览器 `注册 429 → 登录 200` 已通过**。先前共享额度方案不作为最终验收证据；本模块完成，交维护者审核。
- 前端真实生产预览 `http://127.0.0.1:3004`；后端 `http://127.0.0.1:19334`；专用数据库 schema `portfolio_dev.project_acceptance_20261005`。测试访客 `registration_20261005_01`、最终版本复验访客 `registration_20261006_01`，站长使用隔离夹具账号 `project_owner`，不是正式账号。
- 密码仅从被忽略的本机夹具文件读取；下文统一写为 `[REDACTED]`。不记录 RSA 密文、Cookie、CSRF 值、私钥或数据库凭据。未操作 Docker、OSS 对象或正式云服务器。
- 最终独立限流 UI 复验使用 Redis 前缀 `mai-portfolio:registration-final-20261006`，注册默认额度 **5 次 / 5 分钟**；没有为 UI 测试调整生产阈值。
- 表格是已执行操作的记录，而非待执行清单或可复用自动化脚本。模拟失败和辅助请求均单独说明；不将单元测试当作真实 UI 验收。

## 本轮接口契约

|接口|请求及身份边界|返回与持久化|
|---|---|---|
|POST `/api/v1/auth/register`|匿名可调用；JSON 只绑定 `username`、`encryptedPassword`；必须是允许的 Origin。生产 HTTPS 检查仍生效；注册不要求已有会话或 CSRF|成功 200，`data={"username":"…"}`；固定 NORMAL、ENABLED；只保存 Argon2id 哈希。不自动登录，不返回 Cookie、内部账号 ID、密码哈希或角色配置|
|POST `/api/v1/auth/session`|沿用现有固定 RSA-OAEP SHA-256 密文登录|正常访客登录 200，`roles=[]`；建立既有 HttpOnly Cookie 会话及 CSRF 令牌|
|GET `/api/v1/auth/session`|沿用通用查询，页面先确认会话后才呈现注册表单|匿名 200，`loggedIn=false`、`roles=[]`；已登录账号显示当前身份，不静默切换账号|
|DELETE `/api/v1/auth/session`|沿用当前会话及 CSRF 校验|200；前端清除会话、角色和 CSRF，不保留旧管理权限|

只有精确 `POST /api/v1/auth/register` 豁免登录及 CSRF；相邻路径或其他方法不获得同等豁免。业务层校验用户名、密码和账号判重，不依赖前端替代服务端校验。

下文响应只摘录与结果相关的非敏感字段，不是完整网络转储。状态码与关键 data 来自已执行联调；422/409 的中文提示与当前 SystemMessageConstants 对应，未保存的完整响应文本不补造。最终版本首个注册已实际确认成功 message 为“成功”；早期操作不据此补造完整响应。

## 真实浏览器操作链

|步骤|使用页面、点击按钮|输入参数与实际请求|状态、响应及页面结果|
|---|---|---|---|
|匿名进入注册|登录页 `/login` 点击“创建访客账号”，进入 `/register`|GET `/api/v1/auth/session`|200，匿名；会话确认后显示用户名、密码与“创建账号”按钮，不读取管理数据|
|短密码的前端阻止|注册页填写合法用户名和低于 12 个 Unicode 码点的密码 `[REDACTED]`，点击“创建账号”|客户端规则不通过；**没有 POST register**|显示“密码至少需要 12 个字符。”；密码输入清空，不显示成功状态|
|正常注册|注册页填写 `registration_20261005_01`、夹具密码 `[REDACTED]`，点击“创建账号”|POST `/api/v1/auth/register`，`{username,encryptedPassword:[REDACTED]}`|200/OK，data.username 为该测试用户名；显示“注册成功”和“前往登录”；没有自动建立会话|
|再次确认匿名|注册成功后查询会话|GET session|200，`loggedIn=false`、`roles=[]`，证明注册成功不是登录成功|
|不区分大小写判重|再次打开注册页，填写 `REGISTRATION_20261005_01` 与合法密码 `[REDACTED]`，点击“创建账号”|POST register|409/DATA_CONFLICT；显示“用户名已被使用”，无成功跳转，密码清空，没有重复账号|
|新访客登录|点击“前往登录”，填 `registration_20261005_01` 与同一夹具密码 `[REDACTED]`，点击“登录”|POST session，随后 GET session|200；`loggedIn=true`、`roles=[]`；页面显示访客登录状态，不显示“管理项目”按钮|
|直接管理 URL|访客登录时地址栏输入 `/admin/projects`|路由守卫 GET session|200，真实角色为空；跳到 `/forbidden`，不挂载项目管理页、不请求管理列表|
|伪造管理列表参数|在访客浏览器执行同源辅助请求，不借助管理页面按钮|GET `/api/v1/projects?view=MANAGE`|403/FORBIDDEN；服务端拒绝，不能靠 URL 或前端角色状态获得管理数据|
|正常注销|登录状态页点击“注销账号”|DELETE session，携带正确 CSRF `[REDACTED]`|200；前端登录态、roles、CSRF 清空，之后查询为匿名|
|站长已登录时打开注册页|使用 `project_owner` 在 `/login` 登录后进入 `/register`|POST/GET session 200，roles 包含 OWNER|显示“当前账号已登录”、站长“管理项目”链接及“注销账号后注册”；**不显示注册表单**，不会静默切换身份|
|会话查询失败|临时模拟注册页下一次会话查询失败后进入注册页|该次查询是故障注入，不是后端真实故障|注册表单不显示，展示错误与“重新确认账号状态”；点击重试后真实 GET session 200，恢复应有页面|
|服务端短密码校验|浏览器同源辅助请求，以已配置公钥加密短密码 `[REDACTED]` 后提交，绕过表单的客户端长度规则|POST register，合法用户名、合法 RSA 密文，但解密后不足 12 码点|422/VALIDATION_FAILED，提示“密码至少需要 12 个字符，且 UTF-8 长度不能超过 190 字节”；不创建账号，证明后端独立校验|
|私有页索引限制|地址栏进入 `/register`，另读取该页原始 HTML 与响应头|GET `/register`|HTML meta 为 `noindex,nofollow`，响应含对应 X-Robots-Tag；原始 SSR 输出不含私人登录用户名或已认证表单状态|
|可视化文档|地址栏打开后端 `/doc.html`，展开“用户注册”及“普通用户密文注册”|真实 Knife4j 页面读取 OpenAPI|注册标签、RegisterRequest、RegistrationVo/包装响应可见；展示密文与不自动登录、独立限流契约。不在文档输入框提交明文密码|

### 操作误差与修复记录

1. 最初尝试从 `window.__NUXT__` 读取公钥来构造短密码请求，因取值方式不符合当前运行时形态，没有发送有效的短密码 POST。该尝试不计为 422 验收。随后改用已配置公钥的浏览器 IIFE，实际 POST 获得上表 422。
2. 初版 `RegisterRequest` 在 Service 去空格之前执行 `@Size(max=64)`，会错误拒绝“有效 64 位用户名外加首尾空格”。现已在 Request 构造时规范化用户名，再执行 Bean Validation；真实数据库契约测试覆盖 64 位带首尾空格输入并返回 200。密码不参与 trim。
3. 初版用户名 input pattern 的连字符未按浏览器 HTML pattern 的 `v` 模式转义。现改为 `[A-Za-z0-9_\-]+`；页面已复测，无该 pattern 兼容问题。后端和 API 层仍分别保留自己的合法性检查，原生表单不构成唯一保障。
4. 独立限流的首次真实基础设施补测中，RegistrationApiTest 有两项收到非预期 429。主 `application.yml` 与测试 `application.yaml` 同时存在，主配置的注册默认 5 覆盖了测试 YAML 的 256；该测试类会累计多个注册请求，因此提前耗尽额度。现仅在 RegistrationApiTest 的 `@SpringBootTest(properties = "portfolio.security.max-registration-attempts-per-client=256")` 显式隔离测试额度，复测通过；已移除测试 YAML 中本轮新增而无效的注册 256 配置。**没有为使测试通过而提高生产默认阈值**；专门限流测试仍用注册 3、登录 5 的小额度验证拒绝行为。

故障注入和辅助 JavaScript 仅用于本次负向验证，未加入产品代码；完整导航后不依赖这些临时函数。

## 独立限流最终验收

最终实现中的键为 `{REDIS_KEY_PREFIX}:rate:auth:login:{客户端摘要}` 与 `{REDIS_KEY_PREFIX}:rate:auth:register:{客户端摘要}`。两者复用 `FixedWindowRateLimiter`、Redisson 和原子 Lua，但采用各自的额度及窗口配置；注册默认每 5 分钟 5 次，登录维持原配置。成功和失败均占用本业务额度，不因成功重置整个 IP 窗口。

|验证项|要求与证据入口|最终实际结果|
|---|---|---|
|场景独立|LoginThrottleServiceImplTest 验证命名空间、配置不同；注册耗尽不阻止登录和其他客户端|最终完整 install 通过，相关独立计数用例成功|
|双实例并发和幂等竞争|RegistrationRedisFlowTest：两实例同时注册同用户名，结果 200/409、唯一 NORMAL 账号、无自动 Cookie|已启用真实 Redis/数据库，双实例 HTTP 测试通过；竞争结果为 200/409，唯一普通账号及无自动 Cookie 断言成功|
|跨实例注册额度|上述测试注册额度 3，第 4 次返回 429；同客户端随后登录 200、roles=[]|最终双实例测试断言全部通过；旧共享额度测试不作证据|
|跨实例登录额度|同测试登录额度 5，成功登录与失败尝试只占登录额度，第 6 次返回 429|最终双实例测试断言全部通过|
|最新版本 UI 基础链|最新构建 `/register` 填 `registration_20261006_01` 与密码 `[REDACTED]`，点击“创建账号”；随后查会话|POST register 200/OK，message 为“成功”，data.username 为该账号；GET session 200，仍为匿名，不自动登录|
|真实 UI 第 2–5 次注册|每次在 `/register` 填同一 `registration_20261006_01`、重新输入密码 `[REDACTED]`，点击“创建账号”|四次 POST register 均 409/DATA_CONFLICT，“用户名已被使用”；每次密码输入均清空，失败也占注册额度|
|真实 UI 第 6 次注册|再次填同用户名、密码 `[REDACTED]`，点击“创建账号”|POST register 429/RATE_LIMITED，“注册尝试过于频繁，请稍后重试”；密码清空，不展示注册成功|
|真实 UI 注册耗尽后的登录|第 6 次失败后点击“前往登录”，填同账号、同密码 `[REDACTED]`，点击“登录”；与注册耗尽链在同一个 5 分钟窗口内完成|POST session 200/OK；GET session 200，loggedIn=true、roles=[]、csrfToken 存在；页面“欢迎回来”，没有管理按钮。不是等注册窗口过期再登录，真实注册限流没有消耗或封锁登录额度|
|最终 UI 访客管理拒绝|已登录的新访客浏览器执行同源辅助 GET `/api/v1/projects?view=MANAGE`|403/FORBIDDEN，data=null；确认独立额度调整没有赋予管理权限|
|最终 UI 注销清理|登录页点击“注销账号”|DELETE session 200/OK；随后 GET session 200，loggedIn=false、roles=[]、csrfToken 不存在；结束验收会话|
|最新 Knife4j 契约|打开最新后端 `http://127.0.0.1:19334/doc.html`，展开注册接口|真实可视化文档显示“注册 IP 限流与登录分别计数”、Origin header required=true、RegisterRequest 与 RegistrationApiVo|

没有新增全局总额度、验证码、邮箱验证或另一种限流协议；这些不属于本轮需求。

最终已关闭本轮独立浏览器会话；保留 19334 后端与 3004 前端预览供维护者审核，没有为了结束浏览器验收关闭预览服务。

## 测试、构建与覆盖边界

- 前端最终已执行 Vitest **87/87**、Nuxt 类型检查及生产构建，通过。注册测试覆盖输入边界、Unicode 码点、190 字节上限、多字节密码、只 trim 用户名、真实 Web Crypto 密文与解密、无明文/角色提交、加密不可用不发请求、409/429 不重试、缺失成功 data 不假成功。
- 独立注册限流调整后的最终后端 install **31 个模块成功，124 总计、84 执行、40 跳过，零失败**。
- 随后启用真实数据库及 Redis 的补充测试 **14/14 通过**：AuthSecurityTest **8**、RegistrationApiTest **5**、RegistrationRedisFlowTest **1**。该结果包括修正测试配置覆盖后的复测，不隐藏上述首次 429 失败。
- RegistrationServiceTest 覆盖固定启用普通账号、无 StpUtil 会话副作用、Origin/限流先于昂贵密码运算、用户名规则、Unicode/UTF-8 规则、判重不做 Argon2、并发唯一索引冲突映射 409。
- RegistrationApiTest 为显式启用的真实数据库契约，覆盖密文注册与登录、密码空格、Argon2id、普通账号拒绝管理、注销、额外 type/status/roles 不能赋权、大小写判重、64 位用户名 trim、明文/密文和 Origin 错误、短密码拒绝。本类在普通 install 中被环境开关跳过，随后真实基础设施补测 **5/5** 通过；未实际启用的其他跳过用例仍不算已验证。
- AuthenticationInterceptorTest 覆盖精确 POST 豁免、上下文路径与 HTTPS 检查；已有登录/注销/CSRF 行为保留。SecurityPropertiesTest 覆盖新增注册默认值、独立配置及非正限额拒绝。
- 本轮不重复未改动且已验收的项目、个人资料主链路；不声称完成博客、导出或媒体验收。

## 事实、设计判断与副作用

**Spec 明确要求：** 普通访客能注册登录，不能取得站长管理权限；公开作品集可匿名阅读，首版不要求个人中心。普通账号的文章导出必须等博客许可链路完成后另行验收。

**既有技术约束：** 沿用固定 RSA-OAEP SHA-256、公钥配置、Argon2id、Cookie/CSRF、Sa-Token、PostgreSQL/MyBatis-Plus、统一 R 与异常体系、Request/Vo、提示常量、@ApiLog 敏感过滤及 Knife4j。RSA-2048/OAEP-SHA256 单条明文上限 190 字节；本地 HTTP 不等同于公网安全。

**本轮设计判断：** 用户名采用 3–64 位 ASCII 字母、数字、下划线或连字符；trim 后保存且不区分大小写判重；密码不少于 12 个 Unicode 码点且不 trim；注册成功后显式登录。独立计数是维护者在指出业务互相牵连后确认的方案，不是把共享额度包装成 Spec 要求。LoginThrottleService 的现有名字保留，避免仅为命名重构其他模块。

**持久化与外部副作用：** 注册事务创建 user_account 的自增 ID、NORMAL/ENABLED、Argon2id 哈希和 UTC 时间。服务端先判重，数据库 LOWER(username) 唯一索引处理并发竞争并映射 409；不返回数据库底层错误。Redis 保存对应业务窗口与 TTL；后续显式登录/注销改变 Sa-Token 会话。没有写入 OSS、消息系统或 Agent 状态。双实例测试仅清理自己生成的唯一账号与独立 Redis 前缀，浏览器验收账号保留在隔离库供审核。

## 风险与未验证事项

- 本机验收 Redis 监听 6379；**精确 Redis 服务端 3.2.1 兼容尚未确认**，不能由 Lua 使用低版本命令或当前测试通过推导出全部兼容。
- 未完成公网 HTTPS、实际反向代理地址恢复、共享 NAT 公平性及攻击级防刷验收。当前依据容器直接 remoteAddr，不信任客户端自行提交的转发头；上线时必须明确可信代理边界，否则同源代理可能把不同用户计为一个 IP。
- 用户名重复返回 409，可能被用于确认用户名存在；该可用性与枚举风险需维护者在部署安全评审时决定。RSA 密文可被重放，HTTP 仍可能被窃听/篡改；本轮没有声称替代 TLS。
- 无邮箱验证、找回密码、个人中心、验证码和总量限流；这些不是本轮已交付能力。允许普通账号注册不意味着博客导出已经开放。
- 页面错误、重试和权限边界已实际联调；移动设备、浏览器矩阵、代理多实例部署、故障时 Redis 不可用的 UI 专项链路尚未验证。

## 定向阅读清单

- System Service：`controller/RegistrationController.java`；`entity/request/RegisterRequest.java`；`entity/vo/RegistrationVo.java`、`RegistrationApiVo.java`；`service/AuthService.java`、`impl/AuthServiceImpl.java`；`auth/AuthenticationInterceptor.java`；`service/LoginThrottleService.java`、`impl/LoginThrottleServiceImpl.java`；`config/SecurityProperties.java`、`constant/SystemMessageConstants.java`。
- System Provider：`api/AuthConstants.java`；Redis Starter：`define/cache/RedisKeys.java`，复用 `FixedWindowRateLimiter`、`rate/RedissonFixedWindowRateLimiter.java`、`define/RedisLuaScripts.java`。
- 后端测试：`RegistrationApiTest.java`、`RegistrationRedisFlowTest.java`、`RegistrationServiceTest.java`、`AuthenticationInterceptorTest.java`、`LoginThrottleServiceImplTest.java`、`SecurityPropertiesTest.java`。
- 前端：`app/pages/register.vue`、`app/pages/login.vue`；`app/api/registration.ts`、复用 `loginCrypto.ts` 与 `request.ts`；`app/constants/registration.ts`、`app/test/registration.test.ts`；`nuxt.config.ts` 的私有页 noindex header。
- 配置/说明：`backend/mai-portfolio-launch/src/main/resources/application.yml`、`backend/run-local.ps1`、`.env.example`、`README.md`。维护者本地 `application-dev.yaml` 修改保留，**不纳入提交**；环境文件、密码、日志、截图与构建产物不提交。

本模块完成最终验证、提交及推送后停止供维护者审核。未获得明确审核通过，不合并，不继续新增博客功能；首版总目标仍包含尚未实现的博客与 Markdown 导入导出。
