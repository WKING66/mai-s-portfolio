# 阿霾的个人作品集

项目正在实施中，正式内容不会自动填入演示项目或测试笔记。架构为独立的 Nuxt 4 前端、Spring Boot 4 后端、PostgreSQL 和 Redis；本地开发不依赖 Docker。前端使用 Tailwind CSS 4，通用设计令牌集中在 `frontend/app/assets/css/main.css`，组件特有的动效保留在组件样式中；数据请求继续使用 Nuxt 内置能力。

后端是同一 JVM 部署的 Maven 模块化单体，结构参考 `orion-visor-w`：`mai-portfolio-dependencies` 管理版本；`mai-portfolio-common` 只容纳已经确认可跨业务域复用的公共能力、通用工具和通用模型，目前包含无框架依赖的统一响应模型、通用响应提示与分布式锁抽象；`mai-portfolio-framework` 将 Web、参数校验、接口日志、Sa-Token 安全、Redis/Redisson、MyBatis、数据源、Flyway、OpenAPI/Knife4j、对象存储分别封装为可复用 Starter；`mai-portfolio-modules` 下的 System（账号/鉴权）、Portfolio（个人资料/项目）、Asset（媒体元数据/授权）、Taxonomy（技术与内容标签）、Blog、Agent 业务域各自拆成 provider 与 service；`mai-portfolio-launch` 是唯一启动模块并拥有应用级配置及数据库迁移。业务模块不是 Starter；跨域只能依赖对方 provider，禁止引用对方 Mapper、DO 或实现类。每个 service 内部继续使用 Controller → Service 接口/impl → Mapper 三层结构；HTTP 入参统一放 `entity/request` 并以 `Request` 结尾，返回前端的实体统一放 `entity/vo` 并以 `Vo` 结尾。

## 本地启动

准备 Java 21、Maven、Node.js 24、PostgreSQL 和 Redis。仓库根目录的 `.env`（已被 Git 忽略）需包含 `PSQL_HOST`、`PSQL_PORT`、`PSQL_USERNAME`、`PSQL_PASSWORD`、`REDIS_HOST`、`REDIS_PORT`、`REDIS_DATABASE`、可为空的 `REDIS_PASSWORD` 和至少 12 字符的 `OWNER_PASSWORD`；后端开发脚本使用 `dev` 配置，并始终连接独立的 `portfolio_dev` 库，不会使用该文件里的其他库名。首次运行前只对专用开发库执行 Flyway 迁移，不要将此配置指向已有业务库。初始站长密码只在首次建号时哈希保存，后续修改 `.env` 不会重置已存在账号。

Redis 由 Redisson 统一接入。项目自有键统一使用 `REDIS_KEY_PREFIX` 命名空间，动态客户端标识先做 SHA-256 摘要；当前承担 Sa-Token 会话、登录限流、一次性登录挑战与分布式锁。登录挑战在 Redis 中按 TTL 保存并原子消费，多实例部署时不会因请求落到另一实例而失效。当前实现只使用 Redis 3.2 可用的 Lua、过期和映射命令，不使用 Redis 6 的 `KEEPTTL`；精确的 Redis 3.2.1 兼容验收仍须连接真实 3.2.1 服务执行。

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

前端开发地址：<http://127.0.0.1:3000>，站长登录页：<http://127.0.0.1:3000/admin/login>。后端本地 Knife4j 可视化文档：<http://127.0.0.1:9333/doc.html>，原始 OpenAPI 数据：<http://127.0.0.1:9333/v3/api-docs>。当前已验证站长会话、公开资料和站长资料管理接口的文档、认证边界和响应结构；项目、博客与媒体接口将在各自实现时同步补充。正式部署前需同时关闭或保护 Knife4j 页面与 OpenAPI 数据端点。

站长登录协议：`GET /api/v1/admin/session/challenge` 获取 60 秒有效、一次性的 RSA-OAEP SHA-256 公钥与 `challengeId`；前端用 Web Crypto 加密密码后，`POST /api/v1/admin/session` 只提交 `username`、`challengeId`、`encryptedPassword`。旧明文 `password` 字段不再接受。一次性私钥及客户端绑定信息以 TTL 保存在 Redis，提交时先原子消费再解密；重放、过期或 Redis 数据丢失时需重新获取公钥。RSA-2048/OAEP SHA-256 限制密码为最多 190 个 UTF-8 字节。Knife4j 可查看两接口，但不会自动执行浏览器加密；请用前端登录页验证完整流程。

站长资料管理：登录后调用 `GET /api/v1/admin/profile` 读取当前资料和 `updatedAt`；`PATCH /api/v1/admin/profile` 须提交全部五个可编辑文本字段、原样带回 `updatedAt`，并附上会话中的 `X-CSRF-Token`。GitHub、邮箱传 `null` 或空白可取消公开；过期的 `updatedAt` 返回 `409`，头像、简历和 SEO 配置不会被此接口修改。

本地开发启动成功后，终端会打印 Knife4j 与 OpenAPI 地址。开发后端仅监听 `127.0.0.1`；`portfolio.api-log.enabled` 在 `dev` 环境默认开启，其余环境默认关闭。AOP 只记录显式标注 `@ApiLog` 且已经进入 Controller 的接口调用，记录路由、耗时及截断后的入参/出参；密码、令牌、Cookie、邮箱、Markdown 正文等字段内置脱敏，文件/字节流直接省略。需要额外隐藏业务字段时配置 `portfolio.api-log.additional-sensitive-fields`，单条内容上限由 `portfolio.api-log.max-payload-length` 控制。未标注接口以及鉴权拦截阶段拒绝的请求不经过 Controller AOP。

传输安全：本地回环 HTTP 仅供开发。请求体中的密码现已用短时 RSA-OAEP 公钥加密，但 HTTP 下公钥及前端脚本都可能被中间人替换，因此这**不能替代 HTTPS**；非本机 HTTP 浏览器通常也不开放 Web Crypto。正式部署的 HTTPS、可信反向代理、安全 Cookie 和文档访问策略待部署阶段确认，当前代码不得直接作为公网安全配置使用。

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
