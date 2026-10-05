# 阿霾的个人作品集

项目正在实施中，正式内容不会自动填入演示项目或测试笔记。架构为独立的 Nuxt 4 前端、Spring Boot 4 后端、PostgreSQL 和 Redis；本地开发不依赖 Docker。前端使用 Tailwind CSS 4，通用设计令牌集中在 `frontend/app/assets/css/main.css`，组件特有的动效保留在组件样式中；数据请求继续使用 Nuxt 内置能力。

后端是同一 JVM 部署的 Maven 模块化单体，结构参考 `orion-visor-w`：`mai-portfolio-dependencies` 管理版本；`mai-portfolio-common` 只容纳已经确认可跨业务域复用的公共能力、通用工具和通用模型，目前包含无框架依赖的统一响应模型、通用响应提示与分布式锁抽象；`mai-portfolio-framework` 将 Web、参数校验、接口日志、Sa-Token 安全、Redis/Redisson、MyBatis、数据源、Flyway、OpenAPI/Knife4j、对象存储分别封装为可复用 Starter；`mai-portfolio-modules` 下的 System（账号/鉴权）、Portfolio（个人资料/项目）、Asset（媒体元数据/授权）、Taxonomy（技术与内容标签）、Blog、Agent 业务域各自拆成 provider 与 service；`mai-portfolio-launch` 是唯一启动模块并拥有应用级配置及数据库迁移。业务模块不是 Starter；跨域只能依赖对方 provider，禁止引用对方 Mapper、DO 或实现类。每个 service 内部继续使用 Controller → Service 接口/impl → Mapper 三层结构；HTTP 入参统一放 `entity/request` 并以 `Request` 结尾，返回前端的实体统一放 `entity/vo` 并以 `Vo` 结尾。

## 本地启动

准备 Java 21、Maven、Node.js 24、PostgreSQL 和 Redis。仓库根目录的 `.env`（已被 Git 忽略）需包含 `PSQL_HOST`、`PSQL_PORT`、`PSQL_USERNAME`、`PSQL_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_DATABASE`、可为空的 `REDIS_PASSWORD` 和至少 12 字符的 `OWNER_PASSWORD`；后端开发脚本使用 `dev` 配置，并始终连接独立的 `portfolio_dev` 库，不会使用该文件里的其他库名。首次运行前只对专用开发库执行 Flyway 迁移，不要将此配置指向已有业务库。初始站长密码只在首次建号时哈希保存，后续修改 `.env` 不会重置已存在账号。

Redis 由 Redisson 统一接入。项目自有键统一使用 `REDIS_KEY_PREFIX` 命名空间，动态客户端标识先做 SHA-256 摘要；当前承担 Sa-Token 会话、IP 固定窗口限流与分布式锁。登录使用 `auth:login`、注册使用 `auth:register` 命名空间，两者复用限流组件和 Lua，但各自计数，成功或失败都占用本业务额度。认证使用部署配置中的固定 RSA 公钥，不再签发或消费一次性登录挑战。当前实现只使用 Redis 3.2 可用的 Lua、过期和映射命令，不使用 Redis 6 的 `KEEPTTL`；精确的 Redis 3.2.1 兼容验收仍须连接真实 3.2.1 服务执行。

本地 `dev` 启动默认使用真实私有 OSS，PostgreSQL 只保存 Asset 元数据。根目录 `.env` 须设置 `MEDIA_STORAGE=oss`，并按 `.env.example` 填写完整 HTTPS endpoint、Bucket 和 AccessKey；启动脚本会在配置缺失或 endpoint 不是 HTTPS 根地址时直接失败。`local` 实现只供自动化测试或显式离线排障，字节写入被 Git 忽略的 `.local-media`。AccessKey 不进入前端、接口响应或版本库。两种实现都拒绝覆盖同名对象，业务层后续通过新增 Asset/对象键完成版本更新。

真实 OSS 集成测试固定使用现有私有 Bucket `amai-portfolio`，并在其 `integration-tests/` 前缀创建唯一临时对象，验证私有 ACL、上传、读取、禁止覆盖和删除，测试结束后只清理该随机测试对象。它默认不随普通回归访问网络；使用专用脚本从被忽略的 `.env` 注入凭据，且不会打印凭据。脚本会校验 Bucket 名，防止误操作其他 Bucket：

```powershell
cd backend
.\test-oss.ps1
```

真实 Redis 集成测试同样默认关闭。它从 `.env` 读取连接参数，只操作随机测试命名空间并在结束后清理，验证原子限流、一次性数据与分布式锁：

```powershell
cd backend
.\test-redis.ps1
```

启动后端：

```powershell
cd backend
.\run-local.ps1
```

```powershell
cd frontend
npm ci
npm run dev
```

前端开发地址：<http://127.0.0.1:3000>，通用登录页：<http://127.0.0.1:3000/login>，普通用户注册页：<http://127.0.0.1:3000/register>。后端本地 Knife4j 可视化文档：<http://127.0.0.1:9333/doc.html>，原始 OpenAPI 数据：<http://127.0.0.1:9333/v3/api-docs>。普通用户和站长共用会话接口，只有有效 OWNER 能访问管理页面和接口；公开阅读不要求登录。正式部署前需同时关闭或保护 Knife4j 页面与 OpenAPI 数据端点。

通用认证协议：前端从 `NUXT_PUBLIC_AUTH_RSA_PUBLIC_KEY` 读取固定 SPKI 公钥，后端从 `AUTH_RSA_PRIVATE_KEY_LOCATION` 读取外部 PKCS#8 RSA-2048 私钥。前端用 Web Crypto 的 RSA-OAEP SHA-256 加密密码，`POST /api/v1/auth/session` 仅提交 `username`、`encryptedPassword`；校验后设置 HttpOnly Cookie，并返回角色和 CSRF 令牌。`GET` 查询会话，`DELETE` 注销且要求 `X-CSRF-Token`。没有挑战、公钥获取接口或客户端 SHA-256 密码摘要。RSA 单次明文上限为 190 个 UTF-8 字节；Knife4j 不自动加密密码，请用登录页验证。

普通用户注册：`POST /api/v1/auth/register` 使用相同的密文请求字段和来源校验，不要求预先登录或 CSRF。用户名去首尾空格后须为 3–64 位 ASCII 字母、数字、下划线或连字符；不区分大小写判重，重复返回 `409`。密码至少 12 个 Unicode 字符（code point）、最多 190 个 UTF-8 字节，密码不去空格。以上规则均由服务端校验；账号固定为启用的 NORMAL，额外的类型/状态/角色字段不能改变这些值。数据库只保存 Argon2id 哈希。注册成功返回用户名，不自动登录；普通账号没有管理权限。注册限流单独使用 `portfolio.security.max-registration-attempts-per-client`（默认 5）和 `registration-attempt-window`（默认 5m），可由对应的 `PORTFOLIO_SECURITY_*` 环境变量覆盖；不会消耗登录额度。当前尚未实现邮件验证、找回密码或个人中心，博客导出仍待博客模块完成。

站长资料管理：登录后调用 `GET /api/v1/admin/profile` 读取当前资料和 `updatedAt`；`PATCH /api/v1/admin/profile` 须提交全部五个可编辑文本字段、原样带回 `updatedAt`，并附上会话中的 `X-CSRF-Token`。GitHub、邮箱传 `null` 或空白可取消公开；过期的 `updatedAt` 返回 `409`，头像、简历和 SEO 配置不会被此接口修改。

本地开发启动成功后，终端会打印 Knife4j 与 OpenAPI 地址。开发后端仅监听 `127.0.0.1`；`portfolio.api-log.enabled` 在 `dev` 环境默认开启，其余环境默认关闭。AOP 只记录显式标注 `@ApiLog` 且已经进入 Controller 的接口调用，记录路由、耗时及截断后的入参/出参；密码、令牌、Cookie、邮箱、Markdown 正文等字段内置脱敏，文件/字节流直接省略。需要额外隐藏业务字段时配置 `portfolio.api-log.additional-sensitive-fields`，单条内容上限由 `portfolio.api-log.max-payload-length` 控制。未标注接口以及鉴权拦截阶段拒绝的请求不经过 Controller AOP。

传输安全：本地回环 HTTP 仅供开发。请求体中的密码用固定 RSA-OAEP 公钥加密，但 HTTP 下公钥及前端脚本都可能被中间人替换，因此这**不能替代 HTTPS**；非本机 HTTP 浏览器通常也不开放 Web Crypto。生产认证拦截器仍要求 HTTPS；可信反向代理、安全 Cookie 和文档访问策略待部署阶段确认，当前开发配置不得直接作为公网安全配置使用。

## SEO 域名配置

正式域名确定后，在 Nuxt 运行进程中设置 `NUXT_PUBLIC_SITE_URL` 为完整 HTTP(S) 根地址（部署使用 HTTPS）。该地址只能包含协议、域名及可选端口，不接受凭据、业务路径、查询或片段；前端不会从请求 Host 推断公开域名。生产构建无需为域名重新编译，运行时读取 Nuxt `runtimeConfig.public.siteUrl`。

此项默认留空：首页和项目页标记 `noindex,nofollow`，不输出 canonical、`og:url` 或结构化数据；`/robots.txt` 禁止抓取，`/sitemap.xml` 返回明确的 `503` 配置未就绪状态。配置有效地址后，这两个公开页面生成规范地址与 Open Graph，首页按真实公开资料生成 ProfilePage/Person；项目页按同一公开列表生成 ItemList/CreativeWork，分页位置与外链取真实响应，每页规范到当前页。站点地图当前仅列首页与项目栏目，不添加尚未实现的博客、项目详情或虚构更新时间。后台、登录和无权限页继续禁止索引；收录、排名和 AI 引用需在部署后验证，不能由本地元数据保证。

## 检查

```powershell
cd backend
mvn test
```

```powershell
cd frontend
npm run typecheck
npm test
npm run build
```

涉及真实 PostgreSQL 的结构测试只有设置 `PSQL_PASSWORD` 等环境变量时才运行，且应始终指向 `portfolio_dev`。已执行的验收与提交检查记录保留在 `docs/testing/`；历史报告描述的是各自执行时的实现和验证范围，不能作为当前功能全部通过的证明。

## 仓库收录范围

仓库保留 `backend/`、`frontend/`、必要的 `docs/`、本说明及环境变量配置模板。AI 工作流目录、需求与计划材料、静态原型、测试样例、初期交接材料及技能锁文件只在维护者本地保留，由 `.gitignore` 排除，不属于运行和构建输入。历史文档中的本地材料路径不代表 GitHub 当前版本提供这些文件。此调整不删除维护者的本地材料，也不改写既有 Git 历史。
