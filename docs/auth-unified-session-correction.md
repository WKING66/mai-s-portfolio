# 通用认证与站长授权分离

日期：2026-10-04；功能分支：`feature/auth-unified-session`；父分支：`feature/auth-fixed-rsa`。

父分支上的固定 RSA 重构尚未提交，因此本次提交一并包含已经验证的固定 RSA 与通用会话修正。
父分支保持原有提交不变；提交后等待维护者审核，不自动合并。

## 需求与边界

本次依据用户追加确认：“通用登录认证”和“站长权限”分开，不再创建另一套认证。
固定 RSA 方案文档中的 OWNER 检查保留在管理业务接口，不再作为登录或注销的前提。
这项明确追加的业务要求覆盖 2026-10-01 报告中“仅站长会话”的描述；其他认证设计保持不变。

未修改用户的 dev 配置；未切换 JWT、请求头 Token、客户端 Cookie 存储方式；
未实现注册、博客导出或 Agent 新功能。

## 实际接口与调用链

所有启用账号共用 `/api/v1/auth/session`：

| 方法 | 功能 | 认证/授权边界 |
| --- | --- | --- |
| POST | 登录 | 登录限流、Origin、固定 RSA-OAEP、Argon2、ENABLED；不要求 OWNER |
| GET | 查询当前会话 | 匿名返回 loggedIn=false；已有会话校验账号是否存在且启用 |
| DELETE | 注销 | Sa-Token 登录态及 CSRF；不要求 OWNER |

`AuthController → AuthService → AuthServiceImpl` 编排上述能力。
普通账号与站长使用相同的 UserAccount 表、密码校验、Sa-Token、Redis 与 HttpOnly Cookie。

受保护请求按以下顺序处理：

1. `AuthenticationInterceptor`（order=-100）：HTTPS 边界、登录态、当前账号状态、非安全方法的 CSRF。
2. 框架 `SaInterceptor`：执行已有 `@SaCheckLogin`、`@SaCheckRole` 注解。
3. `AdminAuthorization`（order=10）：仅对 `/api/v1/admin/**` 要求 OWNER。
4. 业务 Controller / Service。

通用认证拦截器覆盖当前的 `/api/v1/auth/**` 与 `/api/v1/admin/**`。
新增其他受保护业务路径时，需要显式纳入认证与 CSRF 覆盖范围；本次未替未实现模块扩充路径。
公开作品集接口不增加登录要求。

## 状态变化的处理

- NORMAL 登录成功仍不能访问站长管理接口；Controller 原有 OWNER 注解保留。
- OWNER 改为 NORMAL 后，已有登录态仍可查询、注销，但后续管理请求被拒绝。
- 停用账号无法新登录、查询有效会话或继续受保护业务；已有会话持有正确 CSRF 时仍允许注销。
- 已删除账号首次使用会话时拒绝请求并注销；再次查询为匿名状态。
- 账号状态和角色按当前数据库读取，没有新增权限缓存。

停用账号不自动销毁所有设备会话：状态检查负责拒绝业务，主动注销负责销毁当前会话。
重新启用后、尚未到期的会话可能恢复使用；本轮未引入全设备踢出规则。

## 删除、修改与兼容性

- 移除站长专用 `AdminSessionController / AdminSessionService / AdminSessionServiceImpl`，改为通用 Auth 类。
- 移除登录、会话查询中的 OWNER 限制；注销只声明 `@SaCheckLogin`。
- HTTPS 和 CSRF 从站长授权拦截器迁移到通用认证拦截器。
- OpenAPI Cookie scheme 改为 `userSession`，Knife4j 展示“用户会话”。
- 前端 `loginOwner` 改为 `login`，补齐共用 getSession / logout；页面通过 GET 恢复状态，注销携带 CSRF。
- 登录页为 `/login`；旧 `/admin/login` 是同一页面的别名，不是第二套登录实现。
- 旧后端 `/api/v1/admin/session` 不保留：仓库内所有调用已迁移，外部调用方需同步更新。
- 旧登录页移动后删除其空目录。

保留固定 RSA-OAEP SHA-256 / MGF1 SHA-256、外部私钥、Argon2、Redis 会话、
登录限流、严格登录 Origin、CSRF、现有日志脱敏和统一异常响应。
无表结构迁移，无新增生产数据写入；会话、CSRF、限流状态仍按原流程写入 Redis。

## 测试与未验证边界

前端：7 项测试通过；Nuxt 类型检查及生产构建通过。
后端：34 个模块完整 verify / 打包成功；74 项测试中 73 项通过、0 失败、0 错误，
1 项真实 OSS 上传测试按显式开关跳过（本轮不涉及存储修改）。

关键回归覆盖普通用户登录/查询/注销、普通用户拒绝后台 GET/PATCH、正确/缺失/错误 CSRF、
站长角色撤销、账号停用、账号删除、凭据异常、Origin、HttpOnly Cookie、日志脱敏与 OpenAPI。
真实 Redis 测试覆盖共享命名空间的会话与限流，以及两个 Spring/Tomcat 实例处理固定 RSA 登录。
普通账号权限测试使用真实 PostgreSQL 和进程内 Sa-Token DAO；双实例 Redis 测试使用站长账号，
两者不是“普通用户跨机器登录”端到端演练。
测试新增/修改账号在事务内回滚；Redis 联调仅清理本轮随机测试命名空间。

仍需部署验收：HTTPS、可信反向代理、安全 Cookie 的浏览器行为、真实独立进程/跨机器。
本次未新增这些部署设施，也未触碰本地 Docker。

## 保留的取舍与风险

- 不改 Cookie 会话模型；GET 用于前端显示当前身份及取 CSRF，不代替服务端鉴权。
- 固定 RSA 不提供一次性密文、防重放或 HTTP 服务端身份保证；继续以 HTTPS 为部署安全基础。
- 保留既有 Sa-Token 会话共享行为及账号 Session 中的 CSRF：再次登录可能刷新同账号其他页面的 CSRF，
  旧页面需重新查询；本轮未改造为设备级会话。
- 状态、角色检查和业务操作之间仍存在并发时间窗口；没有为权限变更加数据库锁或分布式事务。
- 站长 Controller 注解和 admin 路径兜底都保留，可能造成重复角色查询，未顺手优化缓存。
- 账号停用后刷新登录页，GET 返回 403，页面无法重新取得 CSRF；仍持有此前 CSRF 的客户端可注销。
- 普通账号注册及其业务入口未包含在本次任务，不等于已经实现完整访客账户模块。

## 核心阅读入口

- system-service：`controller/AuthController.java`、`service/AuthService.java`、
  `service/impl/AuthServiceImpl.java`、`auth/AuthenticationInterceptor.java`、`auth/AdminAuthorization.java`。
- system-provider：`api/AuthConstants.java`。
- launch：`config/OpenApiConfig.java`、`AuthSecurityTest`、`ServiceArchitectureTest`、
  `OpenApiContractTest`、`RedisAuthFlowTest`。
- frontend：`app/pages/login.vue`、`app/api/session.ts`、`app/api/session.test.ts`。
