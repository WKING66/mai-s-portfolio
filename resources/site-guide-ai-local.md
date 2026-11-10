# 站长数字人本地配置

## Python AI 服务

Python 默认读取 `mai-portfolio-ai-server/.env`，从任意工作目录启动均使用这份文件。使用 `python -m app.keygen` 可以为新环境生成两项启动密钥，已有文件会拒绝覆盖。

| 配置 | 用途 |
| --- | --- |
| `DATABASE_URL` | AI 独立 PostgreSQL 数据库。Windows 本机 Docker 使用 `127.0.0.1:5433/mai_portfolio_ai`，需要填入数据库账号和密码 |
| `AI_SERVER_TOKEN` | Python 校验站间请求的唯一 Bearer 令牌。Spring 的 `site-guide.ai-server-token` 使用同一个值 |
| `CONFIG_ENCRYPTION_KEY` | 加密数据库里的供应商密钥。升级时保留原值，否则已有供应商密钥无法解密 |

迁移和服务都自动读取上述文件。常规开发启动先执行项目的 Alembic 迁移，再运行 FastAPI 的 `app.main:app_factory`。Windows 下 Uvicorn 使用 `asyncio:SelectorEventLoop`。默认本地监听 `127.0.0.1:9344`，就绪接口为 `/internal/v1/health/ready`。

## Spring 网站后端

配置入口在 Spring `application.yml` 的 `site-guide` 节点。开发环境读取已有的 `mai-portfolio-backend/.env`，兼容仓库根目录的 `.env`。从项目根、Java 项目或启动模块目录运行均可。Java 不读取 Python 服务目录的 `.env`。

| 配置 | 用途 |
| --- | --- |
| `site-guide.enabled` | 网站是否开放数字人功能，默认关闭。站长可在关闭状态下先完成配置 |
| `site-guide.ai-base-url` | Spring 访问 Python AI 服务的地址，本地默认 `http://127.0.0.1:9344` |
| `site-guide.ai-server-token` | 从 Java 项目 `.env` 的 `SITE_GUIDE_AI_TOKEN` 或部署环境读取，与 Python 的 `AI_SERVER_TOKEN` 相同 |

管理页显示 Spring 功能开关状态。访客正式问答链路尚待接入。

## 配置中心

用各自原有启动方式运行 Nuxt `3000`、Spring `9333` 和 Python `9344`。使用网站已有站长账号打开 `http://127.0.0.1:3000/admin/agent-settings`。管理接口为 `GET/PUT /api/v1/admin/agent/settings`，使用网站会话、OWNER 权限和写操作 CSRF 校验。

| 页面字段 | 用途 |
| --- | --- |
| Chat / Embedding API Base URL | 分别填写供应商给出的 OpenAI 兼容 HTTPS 基础地址，SDK 会追加接口路径 |
| API 密钥 | 填入对应供应商发放的密钥。页面只显示是否已配置。已有密钥时换地址需要新密钥 |
| 对话模型、向量模型、向量维度 | 供应商要求的模型 ID 和 Embedding 输出维度 |
| 请求超时、输出 Token 上限、计费输入上限 | 控制单次请求边界并计算最大费用预留 |
| 每百万 Token 单价、价格版本、币种、计费时区 | 定义费用预留与结算口径 |
| 每日预算 | 费用上限。默认 0，Python 在外呼前检查预算和价格。换供应商地址时预算自动归零 |
| 回答风格 | 新受理问答使用的 Persona |

已保存的设置优先，未保存的运行字段才使用文件或代码默认值。供应商地址只接受公网 IPv4 可达的 HTTPS 域名。真实模型验收需有效供应商配置和获准测试预算。

AI 每小时清理已终结超过 24 小时的执行正文，保留请求标识用于防重。问答接口见 [聊天接口契约](site-guide-chat-contract.md)。
