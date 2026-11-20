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

在 Spring `application.yml` 的 `site-guide` 节点配置，或通过启动环境注入对应的环境变量。

| 配置 | 用途 |
| --- | --- |
| `site-guide.ai-base-url` | Spring 访问 Python AI 服务的地址，本地默认 `http://127.0.0.1:9344` |
| `site-guide.ai-server-token` | 通过 Spring 配置或环境变量 `SITE_GUIDE_AI_TOKEN` 指定，与 Python 的 `AI_SERVER_TOKEN` 相同 |

站点开关和对话限额由 Spring 的 `site_config` 管理，通过后台「站点与限额」修改。全站限额由匿名访客和登录账号共同使用。个人限额按访客或账号统计最近 24 小时，清空会话或重试同一请求不增加额度。模型供应商自行计费。

## 发布与回退

正式切换先关闭新问答入口，等待在途执行完成，然后备份 Spring 与 Python 数据库。两份备份必须来自同一停写窗口，因为网站轮次引用 Python 执行编号。隔离环境用配套备份恢复两库并启动上一版应用，验证旧会话和旧配置仍可读取。失败时保持聊天关闭，恢复两库和上一版应用后再开放入口。

升级旧库前核对 Java 轮次状态 0（待受理）、1（已受理）和 2（旧版已预占），确认没有遗留，再运行 Flyway。V19 要求两张旧金额账本为空。旧库已有账本记录时，先停止切换，完成备份、归档数量核对及获准的清理。Python 需先收尾旧执行和待上报记录，再运行 Alembic 并按 `requestId` 核对两库关联。

## 配置中心

用各自原有启动方式运行 Nuxt `3000`、Spring `9333` 和 Python `9344`。使用站长账号进入「数字人配置」。模型、执行链和 Prompt 接口位于 `/api/v1/admin/agent/settings/catalog`，站点开关和限额位于 `/api/v1/admin/agent/settings/site`。写入需要站点会话、OWNER 权限和 CSRF 校验。

| 页面字段 | 用途 |
| --- | --- |
| 供应商地址和 API 密钥 | 填供应商的 OpenAI 兼容 HTTPS 地址及对应密钥。读取时只报告是否已配置 |
| 模型 ID、Chat 输出上限与 Embedding 维度 | 使用供应商目录中的完整模型 ID，并按所选能力设置参数 |
| 执行链 | 分别设置 Chat 与 Embedding 的主备顺序 |
| Chat 思考模式 | 跟随模型默认、开启或关闭。仅在供应商和模型支持时生效 |
| Persona | 控制新受理问答的表达风格 |

AI 每小时清理已终结超过 24 小时的执行正文，保留请求标识用于防重。问答接口见 [聊天接口契约](site-guide-chat-contract.md)。

## Python 代码分层

- `app/main.py` 装配服务和数据库连接，管理执行恢复与定时清理的生命周期。
- `app/api/routes` 将执行、配置和健康检查拆成独立的 `APIRouter`，由 `app/main.py` 统一装配。`app/api/dependencies.py` 注入鉴权和服务，`app/api/errors.py` 统一处理脱敏错误响应。公共协议类型放在 `app/contracts`。
- `app/service` 处理执行状态与配置中心保存规则。配置和执行沿用各自的 SQLModel 字段定义。
- `app/workflow` 编排对话生成并维护中文系统提示词，`app/provider` 负责调用供应商及 HTTPS 传输，`app/storage` 管理数据库会话。

## 全站轮次额度与 Redis 恢复

- 部署前确认入口已对全部聊天 HTTP 请求提供短时频率保护，包括重试和无效请求。
- 新轮次必须取得 Redis 的全站滚动窗口许可，默认最近一小时最多 60 轮。Redis 超时或不可用时，新轮次返回 503。已受理轮次的恢复在业务层按 PostgreSQL 的原请求编号处理，站点的请求频率保护和登录本身仍依赖 Redis。
- 切换新版时先停止旧 Java 实例的受理并等待在途事务结束，再启动新版，等 Java 就绪后恢复入口受理。Java 启动时用 PostgreSQL 中当前窗口内的 `quota_at` 和 `request_id` 补齐 Redis，已有登记保留原时间。
- 单机 Redis 短时断连后，原进程和原窗口仍在时，新轮次随连接恢复而恢复。Redis 进程重启后，旧快照可能缺少最近的轮次，新轮次继续返回 503。待 Redis 恢复后停止 Java 并等待旧实例退出，再启动 Java 重建窗口。若 Redis 窗口键被删除，也执行同样的 Java 重启步骤。
