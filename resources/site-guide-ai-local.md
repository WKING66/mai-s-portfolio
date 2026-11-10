# 站长数字人 AI 服务本地运行

## 生成启动密钥文件

在 `mai-portfolio-ai-server` 目录执行：

```text
uv sync --locked
uv run --locked python -m app.keygen
```

Windows 默认写入 `%LOCALAPPDATA%/MaiPortfolio/site-guide.env`，其他系统默认写入 `~/.config/mai-portfolio/site-guide.env`。可用 `--output` 指定受保护的位置。命令只输出路径，已有文件会拒绝覆盖。

| 配置 | 如何填写 | 用途 |
| --- | --- | --- |
| `DATABASE_URL` | 必填。为本机 Docker PostgreSQL 创建独立 AI 数据库后，填入包含库名的连接串 | AI 设置、执行和费用账使用的数据库 |
| `AI_DB_SCHEMA` | 默认 `site_guide` | AI 服务的独立 schema |
| `AI_EXECUTION_TOKEN` | 自动生成 | 网站后端调用问答执行接口时使用 |
| `AI_MANAGEMENT_TOKEN` | 自动生成。Python 和 Spring 使用同一个值 | 网站后端访问 AI 配置中心时使用 |
| `CONFIG_ENCRYPTION_KEY` | 自动生成。长期保管并随部署安全备份 | 加密配置中心保存的供应商密钥。替换此值会导致旧密钥无法解密 |
| `SITE_GUIDE_AI_BASE_URL` | Spring 默认使用 `http://127.0.0.1:9344`。AI 服务地址不同时修改 | Spring 连接 Python AI 服务的内部地址 |
| `MODEL_PROVIDER` | 生成时设置为 `openai-compatible` | 指定 Chat 和 Embedding 使用的协议 |
| `AI_PAID_CALLS_ENABLED` | 文件默认 `false`。页面已保存的开关值优先 | 控制是否允许真实收费调用 |

本机数据库连接格式：

```text
DATABASE_URL=postgresql://<账号>:<密码>@127.0.0.1:5433/mai_portfolio_ai
```

运行账号需要能连接该库。迁移账号需要在库内创建 schema 及管理 AI 自有表。旧环境变量 `AI_DATABASE_URL` 仍兼容，同时设置时以 `DATABASE_URL` 为准。

## 迁移和启动

在 PowerShell 中把 `$aiConfigFile` 设为生成文件的实际路径。以下命令在 `mai-portfolio-ai-server` 目录执行：

```powershell
$aiConfigFile = Join-Path $env:LOCALAPPDATA 'MaiPortfolio/site-guide.env'
uv run --locked --env-file $aiConfigFile alembic upgrade head
uv run --locked --env-file $aiConfigFile uvicorn app.main:app_factory --factory --host 127.0.0.1 --port 9344 --workers 1 --loop asyncio:SelectorEventLoop
```

Alembic 只修改 AI 自有 schema，M1 迁移不依赖 pgvector。Windows 下 Psycopg 异步连接需要 Selector 事件循环。使用 `GET /internal/v1/health/ready` 检查数据库和生效配置。

启动 Spring 前，在启动它的 PowerShell 窗口加载所需的两个字段：

```powershell
$aiConfigFile = Join-Path $env:LOCALAPPDATA 'MaiPortfolio/site-guide.env'
Get-Content -LiteralPath $aiConfigFile | ForEach-Object {
    if ($_ -match '^(AI_MANAGEMENT_TOKEN|SITE_GUIDE_AI_BASE_URL)=(.*)$') {
        Set-Item -LiteralPath "Env:$($Matches[1])" -Value $Matches[2]
    }
}
```

## 在配置中心填写供应商

以站长账号打开后台「数字人配置」页（`/admin/agent-settings`）。网站配置接口使用 `GET/PUT /api/v1/admin/agent/settings`，写入需要网站会话、OWNER 角色和 CSRF。

| 页面字段 | 用途 |
| --- | --- |
| Chat / Embedding API Base URL | 两种能力各自的 OpenAI 兼容 HTTPS 基础地址，例如 `https://api.example.com/v1`。SDK 会追加具体接口路径 |
| Chat / Embedding API 密钥 | 从对应供应商取得。保存后加密，页面只显示是否已配置。已有密钥时换地址须同时填写新密钥，并在保存时关闭收费 |
| 对话模型 / 向量模型 | 供应商要求的模型标识 |
| 向量维度 | 核对 Embedding 响应与检索索引的维度 |
| 请求超时 | 供应商请求超时秒数 |
| 输出 Token 上限、计费输入上限 | 限制单次调用，并在发请求前计算最大费用预留。按供应商核实的口径填写 |
| 输入/输出单价、价格版本 | 用于费用预留和结算，单价按每百万 Token 填写，价格版本标记本次定价 |
| 币种、计费时区、每日预算 | 统一账本币种、预算换日时间和每日费用上限 |
| 回答风格 | 新受理问答使用的 Persona |
| 开启真实模型收费调用 | 核对地址、模型、密钥、价格和获准预算后再开启 |

已保存的设置优先，未保存的运行字段才使用文件或代码默认值。配置损坏时就绪检查返回 503。供应商地址只接受公网 IPv4 可达的 HTTPS 域名（443 端口），传输禁止重定向。供应商接口若不兼容 OpenAI 协议，需要单独适配。

AI 每小时清理已终结超过 24 小时的执行正文，保留请求标识用于防重。问答接口见 [聊天接口契约](site-guide-chat-contract.md)。
