# 认证与权限调用链

共享上下文直接复用 Sa-Token Redis 会话，保存账号 ID、用户名和角色。普通认证、角色判断不查账号表；调用方直接使用 AccountContext，不经过身份转发服务。密码摘要及数据库账号实体不进入上下文。

## 登录与后续请求

登录时才查账号、验证密码和状态，并建立会话；之后直接取同一份 Redis 身份。

```mermaid
sequenceDiagram
    participant Browser as 浏览器
    participant Gate as AuthenticationInterceptor.preHandle
    participant Auth as AuthServiceImpl
    participant DB as PostgreSQL
    participant Context as AccountContext
    participant Redis as Sa-Token / Redis
    Browser->>Gate: POST /auth/session
    Gate->>Gate: 配置要求 HTTPS 时检查连接
    Gate->>Auth: login（校验参数、来源和限流）
    Auth->>DB: authenticateLocked（查询账号并验证密码）
    Auth->>Auth: startSession（检查状态、确定角色）
    Auth->>Redis: StpUtil.login + 保存 CSRF
    Auth->>Context: set（账号 ID、用户名、角色）
    Context->>Redis: 保存 identity
    Auth-->>Browser: SessionVo
    Browser->>Gate: 后续受保护请求
    Gate->>Context: currentAccount
    Context->>Redis: 读取登录身份
    Gate->>Gate: 写操作检查 CSRF
    Gate-->>Browser: 交给接口角色授权与业务处理
```

## 权限和业务职责

```mermaid
flowchart TD
    A[请求] --> B{公开只读接口？}
    B -- 是 --> C[直接执行业务，不要求登录]
    B -- 否 --> D[AuthenticationInterceptor：HTTPS、Redis 身份、CSRF]
    D --> E{授权方式}
    E -- 固定 OWNER 接口 --> F[SaInterceptor 执行角色注解]
    E -- OwnerView 且 view=MANAGE --> G[preHandle 检查 OWNER]
    E -- 普通登录接口 --> J[Controller → Service]
    F --> H[AccountRoleProvider.getRoleList]
    G --> H
    H --> I[AccountContext.roles：读取 Redis 角色]
    I --> J
    J --> K[需要当前用户：直接 AccountContext.currentAccount]
    K --> L[业务状态、版本、资源归属检查及数据操作]
```

同一入口不叠加两种 OWNER 检查。公开/管理混合查询保留 OwnerView 条件授权；公开请求不因此变成登录接口。上传入口按用途检查角色；AssetServiceImpl.resource 只保留上传者或 OWNER 的资源访问规则。

BlogServiceImpl、AssetServiceImpl 和账号资料服务直接读取共享上下文，不再通过身份 Service 转发。读写博客、资料和资源仍按业务需要访问数据库。

## 注销、改密与账号访问调整

```mermaid
flowchart LR
    A[AuthServiceImpl.logout] --> B[StpUtil.logout：注销当前登录]
    C[AccountProfileServiceImpl.changePassword] --> D[lockAccount：锁定本人账号、验证旧密码并更新]
    D --> E{事务结果}
    E -- 提交 --> F[内部 LogoutAfterCommit.afterCommit]
    F --> G[StpUtil.logout accountId：注销该账号全部登录]
    E -- 回滚 --> H[保留原登录，不执行注销]
```

站长通过 `PATCH /api/v1/account/{accountId}/access` 提交 `{status: "DISABLED"}` 或 `{type: "NORMAL"}`（也可同时传入两项）。停用或改变类型成功提交后，`AccountProfileServiceImpl.updateAccess` 注销目标账号全部登录；回滚不注销。启用账号后需要重新登录。接口只维护访问能力，不增加账号列表或另一套管理系统。

账号资料写操作自己读取并锁定数据库账号，不把这一步放进所有请求的鉴权链。改密后的注销必须等数据库提交成功；这一步保留为账号资料服务内部的事务回调。

## 关键代码

| 职责 | 位置及方法 |
| --- | --- |
| 共享身份 | security starter：security.context.AccountContext.currentAccount/set/roles |
| 登录、恢复、注销 | system：service.impl.AuthServiceImpl.login/current/logout |
| HTTPS、身份与 CSRF | system：auth.AuthenticationInterceptor.preHandle |
| 角色回调 | system：auth.AccountRoleProvider.getRoleList |
| 改密、账号访问调整与提交后注销 | system：service.impl.AccountProfileServiceImpl.changePassword/updateAccess、LogoutAfterCommit.afterCommit |

## 已确认的取舍

- 直接改数据库账号状态或角色不会实时刷新 Redis 身份；重新登录刷新，需立即失效时显式注销该账号会话。
- 旧会话不含当前身份结构时要求重新登录，不查库回填。
- Redis 故障按错误处理，不回退到数据库鉴权。
- 改密提交后 Redis 注销失败会报错，已提交的密码不会回滚；没有后台补偿或逐请求查库兜底。
- 角色含义、CSRF/HTTPS 与资源归属规则不变；新增的访问调整接口仅供 OWNER，不新增依赖、表或基础设施。
