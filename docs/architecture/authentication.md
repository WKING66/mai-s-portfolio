# 认证与授权

账号登录与 OWNER 权限是两个不同边界。普通用户和站长共用 Account、会话及密码处理能力，角色决定可执行操作，不另建站长登录体系。

## 登录与会话

| 接口 | 职责 |
| --- | --- |
| `POST /api/v1/auth/register` | 创建启用的普通账号；不自动登录 |
| `POST /api/v1/auth/session` | 校验凭据并建立会话 |
| `GET /api/v1/auth/session` | 返回登录状态、角色和 CSRF 信息；匿名可读 |
| `DELETE /api/v1/auth/session` | 注销当前会话，要求 CSRF |

前端使用固定 SPKI 公钥，以 RSA-OAEP SHA-256 加密密码；后端使用外部 PKCS#8 私钥解密。登录字段为 `username`、`encryptedPassword`，注册复用该凭据格式。没有公钥签发或一次性 Challenge 接口。

数据库只保存 Argon2id 密码哈希。Sa-Token 负责会话，Redis 保存会话数据，Cookie 使用 HttpOnly；写请求携带 `X-CSRF-Token`。HTTPS 是部署安全基础，RSA 不替代 HTTPS。运行配置说明见 [快速开始](../../quickstart.md)。

## 权限与失效

- 服务端在受保护请求中校验登录态、当前账号状态及权限；前端持久化快照仅用于展示。
- 普通用户可以管理本人账号资料与头像；网站资料、项目和博客管理要求 OWNER。
- OWNER 撤权后仍可作为普通账号使用，但管理请求被拒绝。
- 禁用账号不能继续受保护业务，仍可显式注销。账号删除后旧会话注销，查询返回匿名状态。
- 博客公开阅读无需登录；导出要求登录，并受文章发布状态及下载设置约束。
- 静态资源以私有 OSS 签名 URL 访问；业务下架限制公开文章入口，不撤销已签发的资源链接。

## 职责与配置

`AuthController → AuthService → AccountAuthenticationService` 编排会话与认证；认证拦截器负责请求的登录状态和 CSRF，角色注解及业务授权负责操作权限。密码规则、解密和哈希复用现有组件，不写入 Controller 或 DTO。

前端公钥由 `NUXT_PUBLIC_AUTH_RSA_PUBLIC_KEY` 提供，后端私钥位置由 `AUTH_RSA_PRIVATE_KEY_LOCATION` 提供。凭据和私钥不进入版本库或日志。登录与注册有各自的限流额度；用户名不区分大小写判重。
