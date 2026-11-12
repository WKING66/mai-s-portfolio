# 站长数字人本地配置

## Python AI 服务

Python 默认读取 `mai-portfolio-ai-server/.env`，从任意工作目录启动均使用这份文件。使用 `python -m app.keygen` 可以为新环境生成两项启动密钥，已有文件会拒绝覆盖。

| 配置 | 用途 |
| --- | --- |
| `DATABASE_URL` | AI 独立 PostgreSQL 数据库。Windows 本机 Docker 使用 `127.0.0.1:5433/mai_portfolio_ai`，需要填入数据库账号和密码 |
| `AI_SERVER_TOKEN` | Python 校验站间请求的唯一 Bearer 令牌。Spring 的 `site-guide.ai-server-token` 使用同一个值 |
| `CONFIG_ENCRYPTION_KEY` | 加密数据库里的供应商密钥。升级时保留原值，否则已有供应商密钥无法解密 |
| `JAVA_MODEL_BUDGET_URL` | Java 内部预算接口地址，供 Python 在外呼前申请预占和上报用量 |

迁移和服务都自动读取上述文件。常规开发启动先执行项目的 Alembic 迁移，再运行 FastAPI 的 `app.main:app_factory`。Windows 下 Uvicorn 使用 `asyncio:SelectorEventLoop`。默认本地监听 `127.0.0.1:9344`，就绪接口为 `/internal/v1/health/ready`。

## Spring 网站后端

在 Spring `application.yml` 的 `site-guide` 节点配置，或通过启动环境注入对应的环境变量。

| 配置 | 用途 |
| --- | --- |
| `site-guide.enabled` | 网站是否开放数字人功能，默认关闭。站长可在关闭状态下先完成配置 |
| `site-guide.ai-base-url` | Spring 访问 Python AI 服务的地址，本地默认 `http://127.0.0.1:9344` |
| `site-guide.ai-server-token` | 通过 Spring 配置或环境变量 `SITE_GUIDE_AI_TOKEN` 指定，与 Python 的 `AI_SERVER_TOKEN` 相同 |

`site-guide.quota` 管理全站与个人的问答受理额度。全站窗口长度由 `global-window-hours` 设置，窗口内共享上限由 `global-request-limit` 设置，匿名与登录共用。个人额度由 `visitor-daily-turn-limit` 和 `account-daily-turn-limit` 分别设置，按访客 Cookie 或登录账号统计过去 24 小时的受理轮次。额度按每轮时间滚动恢复。同一请求号重试、历史导入、刷新、查询与停止不增加轮次。

管理页显示 Spring 功能开关状态。访客正式问答链路尚待接入。

Spring `site-guide.model-budget` 管理金额策略。使用 `daily-limit` 指定每日上限，默认 0 会暂停供应商外呼。`unlimited` 设为 true 时仍保留预占和结算。`currency` 默认 CNY，应与模型报价币种一致。按上海时区归集支出。

切换前先停用 Spring 的 AI 功能，等待旧 Python 执行结束并停止旧进程，确保旧账本停止写入。运行 Java 与 Python 数据库迁移，将原预算值填入 Java 的 `daily-limit` 和 `unlimited`，再启动新版 Java 和 Python。Python 项目的 `.env` 中回调地址本机为 `http://127.0.0.1:9333/internal/v1/site-guide/budget`。确认两端连通后重新启用 AI。Java 会继续计入旧 Python 账本的未结算预占和切换窗口内的支出。旧账本只记录计费日期，保守计入上海日期及其前后一天，避免旧计费时区不同导致漏算。

Python 只暂存待补报的供应商用量和确定未外呼的预占撤销。Java 暂时不可用时，恢复后按原尝试编号补报；在 Java 确认前，预占仍计入预算。

回滚前先停用 Spring 的 AI 功能并停止新版 Python。旧版 Python 只读自己的账本，回滚期间保持停用。若需要在旧版恢复收费，先核对 Python 待补报记录，以及 Java 账本当天的已结算支出和所有未结算预占，再恢复旧版预算和入口。旧 Python 的数据库覆盖值优先于 `.env`，单改 `.env` 无法覆盖先前保存的不限额或正数预算。

## 配置中心

用各自原有启动方式运行 Nuxt `3000`、Spring `9333` 和 Python `9344`。使用网站已有站长账号打开 `http://127.0.0.1:3000/admin/agent-settings`。管理接口为 `GET/PUT /api/v1/admin/agent/settings`，使用网站会话、OWNER 权限和写操作 CSRF 校验。

| 页面字段 | 用途 |
| --- | --- |
| Chat / Embedding API Base URL | 分别填写供应商给出的 OpenAI 兼容 HTTPS 基础地址，SDK 会追加接口路径 |
| API 密钥 | 填入对应供应商发放的密钥。页面只显示是否已配置。已有密钥时换地址需要新密钥 |
| 对话模型、向量模型、向量维度 | 供应商要求的模型 ID 和 Embedding 输出维度 |
| 请求超时、输出 Token 上限、计费输入上限 | 控制单次请求边界并计算最大费用预留 |
| 每百万 Token 单价、价格版本、币种 | 定义费用预留与结算口径 |
| 回答风格 | 新受理问答使用的风格提示，建议用中文填写。升级时保留已经保存的回答风格 |

已保存的设置优先，未保存的运行字段才使用文件或代码默认值。供应商地址只接受公网 IPv4 可达的 HTTPS 域名。真实模型验收需有效供应商配置和获准测试预算。

AI 每小时清理已终结超过 24 小时的执行正文，保留请求标识用于防重。问答接口见 [聊天接口契约](site-guide-chat-contract.md)。

## Python 代码分层

- `app/main.py` 装配服务和数据库连接，管理执行恢复与定时清理的生命周期。
- `app/api/routes` 将执行、配置和健康检查拆成独立的 `APIRouter`，由 `app/main.py` 统一装配。`app/api/dependencies.py` 注入鉴权和服务，`app/api/errors.py` 统一处理脱敏错误响应。公共协议类型放在 `app/contracts`。
- `app/service` 处理执行状态、费用预占与结算，以及配置中心保存规则。配置和执行沿用各自的 SQLModel 字段定义。
- `app/workflow` 编排对话生成并维护中文系统提示词，`app/provider` 负责调用供应商及 HTTPS 传输，`app/storage` 管理数据库会话。
