# 快速开始与运行注意事项

项目介绍见 [README](../README.md)。运行配置参考仓库根 `.env.example`，真实凭据由本机运行环境注入，不进入 Git。

## 本地启动

准备 Java 21、Maven、Node.js 24、PostgreSQL 和 Redis。IDEA 导入 `mai-portfolio-backend/pom.xml`，运行 `dev.amai.portfolio.PortfolioApplication`，使用 `dev` 配置，默认后端 9333。按 `.env.example` 向 Java 进程注入数据库、Redis、OSS、初始化站长密码及 RSA 私钥位置；本地 `.env` 文件本身不会自动成为 Java 环境变量。前端运行环境提供对应的 SPKI 公钥。

首次启动确认连接的是专用开发库 `portfolio_dev`，不要指向已有业务或生产库。初始化站长密码只在首次建号时保存，修改启动配置不会重置已有账号。数据库初始化与迁移见 [数据库说明](database/README.md)。

Redis 由 Redisson 统一接入。项目自有键统一使用 `REDIS_KEY_PREFIX` 命名空间，动态客户端标识先做 SHA-256 摘要；当前承担 Sa-Token 会话、IP 固定窗口限流与分布式锁。登录使用 `auth:login`、注册使用 `auth:register` 命名空间，两者复用限流组件和 Lua，但各自计数，成功或失败都占用本业务额度。认证使用部署配置中的固定 RSA 公钥，不再签发或消费一次性登录挑战。当前实现只使用 Redis 3.2 可用的 Lua、过期和映射命令，不使用 Redis 6 的 `KEEPTTL`；精确的 Redis 3.2.1 兼容验收仍须连接真实 3.2.1 服务执行。

本地 `dev` 启动默认使用真实私有 OSS，PostgreSQL 只保存 Asset 元数据。向后端运行进程提供 `MEDIA_STORAGE=oss` 和 `.env.example` 中的完整 HTTPS endpoint、Bucket、AccessKey 配置；对象存储配置要求 HTTPS 根地址。`local` 实现只供自动化测试或显式离线排障，字节写入被 Git 忽略的 `.local-media`。AccessKey 不进入前端、接口响应或版本库。两种实现都拒绝覆盖同名对象，业务层后续通过新增 Asset/对象键完成版本更新。

```powershell
cd mai-portfolio-frontend
npm ci
npm run dev
```

前端开发地址：<http://127.0.0.1:3000>，通用登录页：<http://127.0.0.1:3000/login>，普通用户注册页：<http://127.0.0.1:3000/register>。后端本地 Knife4j 可视化文档：<http://127.0.0.1:9333/doc.html>，原始 OpenAPI 数据：<http://127.0.0.1:9333/v3/api-docs>。普通用户和站长共用会话接口，只有有效 OWNER 能访问管理页面和接口；公开阅读不要求登录。正式部署前需同时关闭或保护 Knife4j 页面与 OpenAPI 数据端点。

通用认证协议：前端从 `NUXT_PUBLIC_AUTH_RSA_PUBLIC_KEY` 读取固定 SPKI 公钥，后端从 `AUTH_RSA_PRIVATE_KEY_LOCATION` 读取外部 PKCS#8 RSA-2048 私钥。前端用 Web Crypto 的 RSA-OAEP SHA-256 加密密码，`POST /api/v1/auth/session` 仅提交 `username`、`encryptedPassword`；校验后设置 HttpOnly Cookie，并返回角色和 CSRF 令牌。`GET` 查询会话，`DELETE` 注销且要求 `X-CSRF-Token`。没有挑战、公钥获取接口或客户端 SHA-256 密码摘要。RSA 单次明文上限为 190 个 UTF-8 字节；Knife4j 不自动加密密码，请用登录页验证。

普通用户注册：`POST /api/v1/auth/register` 使用相同的密文请求字段和来源校验，不要求预先登录或 CSRF。用户名去首尾空格后须为 3–64 位 ASCII 字母、数字、下划线或连字符；不区分大小写判重，重复返回 `409`。密码至少 12 位，仅支持英文字母、数字、下划线及可见 ASCII 英文标点；受 RSA 密文流程限制，上限仍为 190 字节，注册密码先去首尾空白后校验及加密，服务端同样规范化后保存；不允许内部空格、其他空白或控制字符。登录维持实际密码匹配，不改已有账号密码。以上规则均由服务端校验；账号固定为启用的 NORMAL，额外的类型/状态/角色字段不能改变这些值。数据库只保存 Argon2id 哈希。注册成功返回用户名，不自动登录；普通账号没有管理权限。注册限流单独使用 `portfolio.security.max-registration-attempts-per-client`（默认 5）和 `registration-attempt-window`（默认 5m），可由对应的 `PORTFOLIO_SECURITY_*` 环境变量覆盖；不会消耗登录额度。当前尚未实现邮件验证或找回密码。

网站资料：公开调用 `GET /api/v1/profile` 读取当前资料和 `updatedAt`；`PATCH /api/v1/profile` 仅限 OWNER，须提交全部五个可编辑文本字段、原样带回 `updatedAt`，并附上会话中的 `X-CSRF-Token`。GitHub、邮箱传 `null` 或空白可取消公开；过期的 `updatedAt` 返回 `409`，头像、简历和 SEO 配置不会被此接口修改。

统一资产：`POST /api/v1/assets` 接收 multipart 的 `file`、`usage`；AVATAR 允许有效登录账号，IMAGE/ATTACHMENT 要求 OWNER。头像、封面、博客插图和附件共用一套上传链路，返回资产 ID、`resourceUrl`、`expiresAt`。头像通过 `PUT /api/v1/account/avatar {"assetId":...}` 绑定本人上传的头像资产；项目封面随项目快照的 `coverMediaId` 绑定。上传者或 OWNER 可 `GET /api/v1/assets/{id}` 刷新地址。网站资料和项目投影直接返回 OSS 地址；普通静态资源没有后端字节代理，项目下架不撤销已签发的地址。私有 Bucket 保持私有，签名默认 1 小时（`MEDIA_SIGNED_URL_TTL`），浏览器直连 OSS。部署前执行 V4 元数据迁移；local 存储只用于离线存储测试，不提供资源直连地址。

项目业务统一在 `/api/v1/projects`，标签在 `/api/v1/tags`；角色由操作权限体现。博客已接入统一 Document：支持 Markdown 导入、唯一工作草稿、显式发布/下架/恢复、草稿与文章删除、标签与导入追踪、资源直连展示及登录后的 MD/ZIP 导出。完整链路及流程图见 [文档导入导出说明](architecture/document-import-export.md)。

本地开发启动成功后，终端会打印 Knife4j 与 OpenAPI 地址。开发后端仅监听 `127.0.0.1`；`portfolio.api-log.enabled` 在 `dev` 环境默认开启，其余环境默认关闭。AOP 只记录显式标注 `@ApiLog` 且已经进入 Controller 的接口调用，记录路由、耗时及截断后的入参/出参；密码、令牌、Cookie、邮箱、Markdown 正文及 OSS 签名地址内置脱敏，文件/字节流直接省略。需要额外隐藏业务字段时配置 `portfolio.api-log.additional-sensitive-fields`，单条内容上限由 `portfolio.api-log.max-payload-length` 控制。未标注接口以及鉴权拦截阶段拒绝的请求不经过 Controller AOP。

传输安全：本地回环 HTTP 仅供开发。请求体中的密码用固定 RSA-OAEP 公钥加密，但 HTTP 下公钥及前端脚本都可能被中间人替换，因此这**不能替代 HTTPS**；非本机 HTTP 浏览器通常也不开放 Web Crypto。生产认证拦截器仍要求 HTTPS；可信反向代理、安全 Cookie 和文档访问策略待部署阶段确认，当前开发配置不得直接作为公网安全配置使用。

## V8 内容模型升级

V8 将旧当前/发布指针迁为工作草稿与正式正文槽位，删除未选中的历史版本及旧 role/alt_text 字段。升级前必须暂停旧后端写入，完成完整可恢复数据库备份，在隔离副本演练后再启动新后端执行 Flyway；不要手工重复执行 SQL。原业务数据需按旧指针核对，原 Asset 文件不删除。新后端不能和 V7 后端同时写同一库，回退需要匹配的代码与数据库备份，不能只回退应用包。

API 的 revision/contentId、标签及新状态动作见 [博客契约](api/blog-document.md)。前端完成适配后再进行页面联调。

## SEO 域名配置

正式域名确定后，在 Nuxt 运行进程中设置 `NUXT_PUBLIC_SITE_URL` 为完整 HTTP(S) 根地址（部署使用 HTTPS）。该地址只能包含协议、域名及可选端口，不接受凭据、业务路径、查询或片段；前端不会从请求 Host 推断公开域名。生产构建无需为域名重新编译，运行时读取 Nuxt `runtimeConfig.public.siteUrl`。

此项默认留空：首页和项目页标记 `noindex,nofollow`，不输出 canonical、`og:url` 或结构化数据；`/robots.txt` 禁止抓取，`/sitemap.xml` 返回明确的 `503` 配置未就绪状态。配置有效地址后，这两个公开页面生成规范地址与 Open Graph，首页按真实公开资料生成 ProfilePage/Person；项目页按同一公开列表生成 ItemList/CreativeWork，分页位置与外链取真实响应，每页规范到当前页。站点地图当前仅列首页与项目栏目；已实现的博客栏目及文章尚未加入，项目详情入口也未列入，不生成虚构更新时间。后台、登录和无权限页继续禁止索引；收录、排名和 AI 引用需在部署后验证，不能由本地元数据保证。

## 检查

```powershell
cd mai-portfolio-backend
mvn test
```

```powershell
cd mai-portfolio-frontend
npm run typecheck
npm test
npm run build
```

功能单元测试及其必要样例随代码维护。本地联调/浏览器/性能脚本和辅助工具不提交，完成使用后清理；报告属于私人材料，不放在对外文档中。不要将 Mock 测试称为真实服务验收。
