# 站长数字人 AI 服务本地运行

## 数据库和配置

本地开发使用 Docker PostgreSQL，经宿主机 `127.0.0.1:5433` 访问。为 AI 服务创建独立数据库，例如 `mai_portfolio_ai`。AI 默认使用 `site_guide` schema。运行账号需要能连接该库，迁移账号需要在库内创建 schema 及管理 AI 自有表。

以 `mai-portfolio-ai-server/.env.example` 为参考，在受保护位置准备环境文件。`DATABASE_URL` 格式如下：

```text
DATABASE_URL=postgresql://<账号>:<密码>@127.0.0.1:5433/mai_portfolio_ai
```

数据库名是连接串的一部分。旧环境变量 `AI_DATABASE_URL` 仍可读取，同时设置时以 `DATABASE_URL` 为准。执行与管理凭证使用两个不同的值，每个至少 32 个字符。数据库口令、服务凭证和模型密钥保存在受保护环境文件中。收费开关默认关闭。

## 配置中心

使用站长账号进入网站后台的「数字人配置」，路径为 `/admin/agent-settings`。在页面保存的回答风格、模型参数、供应商密钥和预算优先于部署文件。未在页面保存的运行字段继续使用部署文件或代码默认值。页面只显示密钥配置状态，留空保留已有值。

首次启动需要数据库连接、两份服务凭证、批准的供应商地址和 `CONFIG_ENCRYPTION_KEY`。密钥写入数据库前以 Fernet 加密。Spring 后端和 Python AI 服务使用同一个 `AI_MANAGEMENT_TOKEN`，Spring 默认访问本机 9344，也可通过 `SITE_GUIDE_AI_BASE_URL` 配置固定管理地址。数据库里的配置损坏时就绪检查返回 503，避免静默恢复到旧预算。网站配置接口使用 `GET/PUT /api/v1/admin/agent/settings`，写入需要网站会话、OWNER 角色和 CSRF。

## 安装和迁移

在 `mai-portfolio-ai-server` 目录下执行以下命令，将 `/path/to/protected.env` 替换为本机配置文件路径：

```text
uv sync --locked
uv run --locked --env-file /path/to/protected.env alembic upgrade head
```

迁移只创建 AI 自有设置、执行快照和费用账。M1 迁移不依赖 pgvector。

运行时由 SQLModel 和 SQLAlchemy 管理异步会话，Psycopg 提供 PostgreSQL 驱动。执行和配置的共有字段在 SQLModel 中定义一次，接口投影只输出约定字段。现有数据库沿用已执行的 Alembic 迁移。

## 启动与检查

```text
uv run --locked --env-file /path/to/protected.env uvicorn app.main:app_factory --factory --host 127.0.0.1 --port 9344 --workers 1 --loop asyncio:SelectorEventLoop
```

Windows 下 Psycopg 的异步连接需要 Selector 事件循环，因此启动命令显式指定它。

使用 `GET /internal/v1/health/live` 查看进程状态，使用 `GET /internal/v1/health/ready` 检查 AI 数据库和迁移状态。健康检查不会触发模型调用。AI 每小时清理已终结超过 24 小时的执行正文，保留请求标识用于防重。接口和重连示例见 [聊天接口契约](site-guide-chat-contract.md)。
