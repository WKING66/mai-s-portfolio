# 认证与权限调用链

普通接口通过现有 Sa-Token Redis 会话取得身份，不查询账号表，也不逐次比对数据库凭据版本。

## 登录：查账号，建立 Redis 上下文

```mermaid
sequenceDiagram
    participant Browser as 浏览器
    participant Gate as AuthenticationInterceptor.preHandle
    participant Auth as AuthController.login / AuthServiceImpl.login
    participant DB as PostgreSQL
    participant Session as AccountAuthenticationServiceImpl.startSession
    participant Redis as Sa-Token / Redis
    Browser->>Gate: POST /auth/session
    Gate->>Gate: RequestSecurityPolicy.requireSecureTransport
    Gate->>Auth: 登录入口不要求已有会话
    Auth->>Auth: 参数、来源、限流、解密
    Auth->>DB: authenticateLocked：读取账号并验证密码
    Auth->>Session: 已验证账号
    Session->>Redis: StpUtil.login：建立 token
    Session->>Redis: AccountContext.set：保存身份及 CSRF
    Auth-->>Browser: Cookie、用户名、角色、CSRF
```

`AccountConvert.toSession` 只复制账号 ID、用户名、类型和状态，不复制密码摘要、凭据版本或数据库审计字段。Redis 命名空间和会话 TTL 沿用现有配置。

## 普通请求：Redis 身份 → 入口授权 → 业务

```mermaid
flowchart TD
    A["浏览器请求"] --> B["AuthenticationInterceptor.preHandle"]
    B --> C{"需要登录或管理视图？"}
    C -- 否：公开只读 --> P["Controller → Service"]
    C -- 是 --> D["RequestSecurityPolicy.requireSecureTransport"]
    D --> E["AccountAuthenticationServiceImpl.currentAccount"]
    E --> F["AccountContext.currentAccount：读取 Redis 会话身份"]
    F --> G{"身份上下文有效？"}
    G -- 否 --> X["认证异常；不回源数据库"]
    G -- 是 --> H{"OwnerView 且 view=MANAGE？"}
    H -- 是 --> I["在 preHandle 中检查一次 OWNER"]
    H -- 否 --> J["写请求核对 CSRF；只读请求跳过"]
    I --> J
    J --> K["SaInterceptor 执行接口注解"]
    K --> L{"固定 OWNER 接口？"}
    L -- 是 --> M["@SaCheckRole：检查一次 OWNER"]
    L -- 否 --> P
    M --> N["AccountRoleProvider.getRoleList → AccountContext.roles"]
    I -. 角色同样来自 .-> N
    N -. Redis 身份中的账号类型 .-> R["角色满足；否则返回 403"]
    M --> P
    P --> Q["业务状态、版本、资源归属等校验 → 执行业务"]
```

- 固定 OWNER 接口使用既有 `@SaCheckRole`。公开/管理共用入口用 `@OwnerView`，只有 `view=MANAGE` 时要求 OWNER；同一入口不叠加两种 OWNER 检查。
- `AssetController.upload` 允许普通账号上传头像，其他用途在入口检查一次 OWNER。
- `AssetServiceImpl.resource` 保留“上传者或 OWNER”规则，不再重新检查 HTTPS、查账号表或调用旧 Owner 服务。
- `BlogServiceImpl`、标签创建和导入记录查询不再重复验证入口角色。内部读取保存结果不重新走授权。
- 业务读取和保存仍按需要访问 PostgreSQL；“不查库”指认证及角色查询。

## 注销与改密

```mermaid
flowchart LR
    A["AuthServiceImpl.logout"] --> B["AccountAuthenticationServiceImpl.logout"]
    B --> C["StpUtil.logout：注销当前 Redis 登录"]
    D["AccountProfileServiceImpl.changePassword"] --> E["读取并锁定本人账号，验证旧密码，更新"]
    E --> F{"数据库事务结果"}
    F -- 提交 --> G["AccountSessionInvalidation.afterCommit"]
    G --> H["invalidateSessions → StpUtil.logout(accountId)：注销全部登录"]
    F -- 回滚 --> I["保留原登录，不执行注销"]
```

账号资料写入属于业务操作；`lockCurrentAccount` 不挂在普通博客鉴权链上。

## 明确的取舍

- 直接在数据库修改账号状态或角色，不会立即改变现有 Redis 身份；重新登录刷新。需要立即失效时显式注销对应账号会话。
- 旧会话没有身份上下文时要求重新登录，不增加查库回填。
- Redis 故障报错，不降级为数据库鉴权。
- 改密提交后删除会话。Redis 删除失败会报错，已提交的密码不会回滚；不再声称数据库凭据版本能在每次请求中兜底。

## 关键代码

类位于各业务 service 模块的 `src/main/java/dev/amai/portfolio/` 下。

| 职责 | 类与方法 | 包 |
| --- | --- | --- |
| 请求入口 | `AuthenticationInterceptor.preHandle` | `system.auth` |
| HTTPS | `RequestSecurityPolicy.requireSecureTransport` | `system.auth` |
| 登录与会话读取 | `AccountAuthenticationServiceImpl.startSession/currentAccount` | `system.service.impl` |
| Redis 身份来源 | `AccountContext.set/currentAccount/roles` | `system.auth` |
| 角色回调 | `AccountRoleProvider.getRoleList` | `system.auth` |
| 改密后注销 | `AccountSessionInvalidation.afterCommit` | `system.auth` |
| 上传用途授权 | `AssetController.upload` | `asset.controller` |
| 资源归属判断 | `AssetServiceImpl.resource` | `asset.service.impl` |
