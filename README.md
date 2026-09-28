# 阿霾的个人作品集

项目正在实施中，正式内容不会自动填入演示项目或测试笔记。架构为独立的 Nuxt 4 前端、Spring Boot 4 后端和 PostgreSQL；首版本地开发不依赖 Docker 或 Redis。前端使用 Tailwind CSS 4，通用设计令牌集中在 `frontend/app/assets/css/main.css`，组件特有的动效保留在组件样式中；数据请求继续使用 Nuxt 内置能力。

## 本地启动

准备 Java 21、Maven、Node.js 24 和 PostgreSQL。仓库根目录的 `.env`（已被 Git 忽略）需包含 `PSQL_HOST`、`PSQL_PORT`、`PSQL_USERNAME`、`PSQL_PASSWORD` 和至少 12 字符的 `OWNER_PASSWORD`；后端开发脚本使用 `dev` 配置，并始终连接独立的 `portfolio_dev` 库，不会使用该文件里的其他库名。首次运行前只对专用开发库执行 Flyway 迁移，不要将此配置指向已有业务库。初始站长密码只在首次建号时哈希保存，后续修改 `.env` 不会重置已存在账号。现有 MySQL→PostgreSQL 开发库迁移记录见 [切库审核点](specs/001-personal-portfolio/postgresql-document-transition.md)。

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

站长登录协议：`GET /api/v1/admin/session/challenge` 获取 60 秒有效、一次性的 RSA-OAEP SHA-256 公钥与 `challengeId`；前端用 Web Crypto 加密密码后，`POST /api/v1/admin/session` 只提交 `username`、`challengeId`、`encryptedPassword`。旧明文 `password` 字段不再接受。一次性私钥只保留在当前后端进程内存，提交后即消费；重放、过期或进程重启需重新获取公钥。RSA-2048/OAEP SHA-256 限制密码为最多 190 个 UTF-8 字节。Knife4j 可查看两接口，但不会自动执行浏览器加密；请用前端登录页验证完整流程。

站长资料管理：登录后调用 `GET /api/v1/admin/profile` 读取当前资料和 `updatedAt`；`PATCH /api/v1/admin/profile` 须提交全部五个可编辑文本字段、原样带回 `updatedAt`，并附上会话中的 `X-CSRF-Token`。GitHub、邮箱传 `null` 或空白可取消公开；过期的 `updatedAt` 返回 `409`，头像、简历和 SEO 配置不会被此接口修改。

本地开发启动成功后，终端会打印 Knife4j 与 OpenAPI 地址。开发后端仅监听 `127.0.0.1`；`portfolio.api-log.enabled` 在 `dev` 环境默认开启，其余环境默认关闭。AOP 只记录已进入 Controller 的接口调用，记录路由、耗时及截断后的入参/出参；密码、令牌、Cookie、邮箱、Markdown 正文等字段内置脱敏，文件/字节流直接省略。需要额外隐藏业务字段时配置 `portfolio.api-log.additional-sensitive-fields`，单条内容上限由 `portfolio.api-log.max-payload-length` 控制。鉴权拦截阶段拒绝的请求不经过 Controller AOP。

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

涉及真实 PostgreSQL 的结构测试只有设置 `PSQL_PASSWORD` 等环境变量时才运行，且应始终指向 `portfolio_dev`。完整需求与后续任务见 [任务清单](specs/001-personal-portfolio/tasks.md)；旧版 [本地验收指南](specs/001-personal-portfolio/quickstart.md) 中的 MySQL/Article/Word/PDF 步骤属于历史计划，当前以切库记录与有效任务为准。
