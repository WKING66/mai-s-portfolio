# 站长数字人聊天接口契约

本文记录当前分支的接口，供联调和验收。站点保存正式问答及匿名历史，浏览器只保留匿名会话 ID，登录后可认领原会话。表结构见 [v3 数据库审核稿](database/site-guide-schema.md)。

浏览器访问网站的 `/api/agent/v1`，网站访问 AI 服务的 `/internal/v1`。两端使用 camelCase JSON 和 UTC RFC 3339 时间戳。会话 ID、执行 ID 为十进制字符串，请求 ID 为 UUID。执行状态为 `accepted`（已受理）、`running`（运行中）、`completed`（已完成）、`failed`（失败）和 `interrupted`（已中断）。

## 网站接口

| 方法和路径 | 请求或响应 |
| --- | --- |
| GET /status | 读取网站是否开启数字人功能。 |
| POST /conversations | 登录用户新建会话。匿名身份首次调用时初始化当前会话，后续重复调用返回同一有效会话。返回 `conversationId`。 |
| GET /conversations | 按最近活动时间列出登录账号的会话，使用有界游标分页。 |
| POST /conversations/claim | 登录成功后由前端自动调用。Spring 用 HttpOnly 访客 Cookie 把仍属于访客的原会话移交当前账号。无需提交正文或访客编号。返回 `conversationId`，没有可转移会话时为空。 |
| GET /conversations/{conversationId} | 向会话所有者返回已保存的问答和当前执行状态。 |
| POST /conversations/{conversationId}/clear | 所有者清空所选会话，返回原会话编号、默认标题和空的问答列表。生成中返回 409，停止后可重试。旧问答不会再展示、进入模型上下文或按原执行编号恢复，已受理的轮次仍计入限额。 |
| POST /conversations/{conversationId}/turns | 接收 `requestId` 和 `question`。前端引用最多 200 个 UTF-16 单元，引用、提示语和问题拼接后的 `question` 最多 300 个 UTF-16 单元。成功受理后以 HTTP 200 和 `text/event-stream` 在同一响应中返回受理事件、快照、首字前进度、正文增量和终态。 |
| GET /executions/{executionId} | 向执行所有者返回快照。 |
| GET /executions/{executionId}/events | 刷新或断线后，执行所有者续订已有执行。 |
| POST /executions/{executionId}/stop | 执行所有者停止执行并获取快照。 |

匿名浏览器只保存当前 `conversationId`，正文由 Spring 保存。首次发送或旧编号返回 404 时，用原访客 Cookie 调用 `POST /conversations` 获取当前会话。登录成功后调用 `POST /conversations/claim` 自动接管原会话。所有会话归属由网站验证。会话和执行编号都是十进制字符串，`requestId` 仍是 UUID。

### 正常发送

```http
POST /api/agent/v1/conversations/42/turns
Content-Type: application/json
Accept: text/event-stream

{"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","question":"这个项目用了什么存储？"}
```

1. 浏览器点发送时先保存 `requestId`，立即显示用户消息和「正在发送」，再用 `fetch` 提交并读取同一响应的 SSE 流。
2. Spring 校验归属、参数与请求去重，记录本次提交的受理截止时间，再调用 Python `POST /executions`。Python 登记执行后返回编号，后台随后沿 Chat 候选链生成。
3. Spring 保存网站请求与 Python 执行的关联，马上在同一 POST 响应中发送首个 `accepted` 事件。浏览器保存 `executionId`，显示「正在生成」。聊天 SSE 使用独立且有容量上限的写线程池。入站校验或限流失败时在开流前返回 HTTP 错误。Python 受理结果尚无法确认时返回 503，浏览器可沿用原请求标识。
4. Spring 订阅 Python 的已有执行，按顺序转发 `snapshot`、`progress` 和 `delta`。即使 Python 在 Spring 连上前已生成正文，首个快照也能补齐。受理事件与正文增量及时刷新，代理不聚合缓冲。
5. Python 保存最终正文和终态。Spring 将正式消息和状态写入网站数据库，成功提交后才向浏览器发送 `terminal`。浏览器断开后，网站仍需跟进并保存最终结果。后台同步失败时，网站恢复后按已有执行 ID 查回补存。

### 刷新、断线和停止

- **已知执行 ID**。刷新后先读会话详情。还在生成时，按原 `executionId` 查询快照或续订 `GET /executions/{executionId}/events`。已保存的终态直接显示。续订只读取原执行。
- **尚未收到受理事件**。用原 `requestId` 和原问题重试同一 POST。Spring 用原截止时间和原输入核对受理状态，按标识查到原执行后在本次响应中接回同一条流。网站先确认原截止时间已经过去，再按请求标识执行最终查询。最终查询确认未受理后，才告诉用户发送未成功。依赖暂不可用时保持待确认。相同标识提交不同问题返回 409。
- **主动停止**。仍使用 `POST /executions/{executionId}/stop`。Spring 核验归属后转发停止并保存结果。刷新和关闭页面不会触发停止。
- **暂时繁忙**。网站返回 `429 CHAT_STREAM_BUSY` 表示本次没有进入 Java 新轮次受理流程。返回 `429 CAPACITY_EXHAUSTED` 表示 Python 拒绝了本次受理尝试，网站已登记的轮次保留，按原规则自然过期。两者在开流前返回 JSON 与 `Retry-After`（秒）。浏览器保留原问题，以同一 `requestId` 有限退避重试；已知执行编号时只重试续订。达到重试上限后可手动继续读取。还没有执行编号时可以取消浏览器等待，之后仍可沿用原请求编号查证。返回 `429 RATE_LIMITED` 表示全站或个人轮次额度已用尽，不自动重试。
- **模型或代理故障**。模型失败作为已保存的终态显示，保留已有正文。代理断线或网站暂时无法确认状态时，页面沿原编号恢复。收到 EOF 但没有 `terminal` 时按断线处理。用户明确重新生成或发出下一问时才使用新的 `requestId`。

## v3 后台设置接口

管理员接口以 `/api/v1/admin/agent/settings` 为基址，沿用 OWNER 权限、网站登录态和写请求 CSRF 校验。成功响应使用网站的 `data` 包装。

| 请求 | 约定 |
| --- | --- |
| `GET /site` | 读取 `agentEnabled`、`globalWindowHours`、`globalRequestLimit`、`visitorDailyTurnLimit`、`accountDailyTurnLimit` 和 `updatedAt`。 |
| `PUT /site` | 提交开关与四个正整数，使用原 `updatedAt` 作为 `expectedUpdatedAt`。配置已被更新时返回 409。开关关闭时仍允许站长编辑设置，已受理的对话继续收尾。 |
| `GET /catalog` | 返回 `providers`、`models` 和 `routes`。供应商密钥只返回 `keyConfigured`。 |
| `POST /catalog/providers`、`PUT /catalog/providers/{id}` | 保存接入卡片。修改带 `expectedRevision`。省略 `apiKey` 时保留旧密钥，更换 API 域名须提交新密钥。 |
| `POST /catalog/models`、`PUT /catalog/models/{id}` | 保存目录模型。修改带 `expectedRevision`。Chat 的 `config` 支持 `maxOutputTokens` 和可选布尔值 `enableThink`，Embedding 的 `config` 支持 `dimensions`。 |
| `PUT /catalog/routes/{type}` | 发送旧顺序 `expectedModelIds` 和新顺序 `modelIds`。批量启用所选模型与替换顺序一起提交。Chat 回复为 1，Embedding 为 2。 |
| `POST /catalog/models/{id}/test` | 单独探测该模型，返回 `ok`、`code` 和 `dimensions`，不改生效链。 |
| `GET /catalog/prompts`、`GET /catalog/prompts/{id}/versions` | 分别返回 `prompts` 和 `versions` 数组。 |
| `POST /catalog/prompts/{id}/versions`、`PUT /catalog/prompts/{id}/active-version` | 发布用 `content`，回退用 `versionId`，两者均带 `expectedActiveVersionId`。 |
| `GET /catalog/wallets` | 返回 `wallets`。DeepSeek 官方接入读取余额与原币种，其他暂未接入余额 API 的钱包返回 `unsupported`。按钱包去重，每批并发查询最多 3 个钱包组。总时限 4 秒，到期返回已完成的余额及未完成钱包的降级状态。 |

## AI 服务接口

| 方法和路径 | 请求或响应 |
| --- | --- |
| POST /executions | 接收 `requestId`、`question`、`history` 和 `acceptBefore`。历史按顺序传入，每项包含 `role` 与 `text`，最多 10 项，来自最近 5 轮已完成问答。返回 `executionId` 和 `status`。 |
| GET /executions/{executionId} | 返回执行快照。 |
| GET /executions?requestId={requestId} | 受理回包不明时，按原请求标识查询。 |
| GET /executions/{executionId}/events | 订阅已经持久化的执行。 |
| POST /executions/{executionId}/stop | 停止执行并返回快照。 |
| GET /settings/catalog | 返回脱敏的供应商、模型及执行链。供应商密钥只报告是否已配置。 |
| /settings/catalog 下的配置写入 | 使用供应商或模型修订号、原执行链或生效 Prompt 版本检查并发修改，具体字段见配置中心接口。 |
| GET /health/live、GET /health/ready | 检查进程存活和依赖就绪，检查期间不会调用模型。 |

网站后端使用同一份站间 Bearer 凭证调用执行和配置接口，浏览器的会话与 OWNER 权限由网站校验。用相同标识和输入重试时，即使超过受理截止时间也返回原执行。超过截止时间的首次提交会被拒绝。上游结果不明时按同一请求标识核对。受理截止后若查不到执行，即可确定原请求无法再受理。

Chat 的思考开关在数据库 `model_config.config` 中保存为 `enable_think`。更新时省略该字段会沿用已保存的值，传 `null` 会恢复线路默认行为。测试连接与聊天使用同一参数转换。智谱直连使用 `thinking.type`，硅基流动和百炼使用 `enable_thinking`。开关能否生效取决于该线路上的具体模型。

## SSE 事件和错误

网站的发送 POST 先发 `accepted`，携带 `requestId`、`executionId` 和当前 `status`。随后同续订接口一样发送 `snapshot`，客户端以其正文覆盖本地累计内容。网站续订 GET 和 Python 订阅 GET 都直接以 `snapshot` 开始。`delta` 的 `offset` 按 UTF-16 代码单元计算，与 Java 和 JavaScript 字符串索引一致。偏移不符时重新订阅并应用下一份快照。`sources` 替换来源列表，M1 阶段为空。`terminal` 携带最终快照。

首字前的 `progress` 只携带 `phase`。页面按实际收到的事件展示步骤，目录如下。

| 阶段 | 页面文案示例 | 触发条件 |
| --- | --- | --- |
| `queued` | 等待开始 | 已受理，等待执行槽。 |
| `preparing` | 正在准备回答 | 已取得执行槽，尚未收到推理信号或可见正文。 |
| `thinking` | 正在思考 | 收到供应商推理信号，尚无可见正文。 |
| `delta` 或带正文的 `snapshot` | 正在输出回答 | 已收到可见正文，之后不再发送前置阶段。 |

阶段可能跳过，快速响应可以从快照直接进入正文。续订从 `snapshot` 开始，尚未有正文时随后收到当前阶段。前端按阶段去重，终态结束等待。推理原文、模型名称及供应商信息不进入进度响应，搜索和来源只按真实工具执行产生。

以下为网站 POST 的成功响应示例。

```text
event: accepted
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"108","status":"accepted"}

event: snapshot
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"108","status":"running","text":"","sources":[],"error":null}

event: progress
data: {"phase":"preparing"}

event: progress
data: {"phase":"thinking"}

event: delta
data: {"offset":0,"text":"项目使用 PostgreSQL。"}

event: terminal
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"108","status":"completed","text":"项目使用 PostgreSQL。","sources":[],"error":null}
```

主动停止保留已保存正文。停止已完成的执行时返回原完成快照。SSE 重连只读已有执行，不触发新生成。网站先提交最终消息和状态，再向浏览器发送终态。

AI 每小时分批清除结束超过 24 小时的临时正文，保留执行与请求标识、状态和 Token 汇总。清理成功执行的正文时记录 `EXECUTION_TEXT_EXPIRED`。网站已落库的问答继续可读。网站尚未落库且发现正文已过期时，将该轮记为失败。

网站的普通 JSON 响应沿用 `code`、`message`、`data` 和 `details`。Python 错误响应结构为 `{"code":"REQUEST_CONFLICT","message":"请求标识已被使用。","requestId":"…"}`。常见状态包括 401（凭证无效）、404（资源不存在）、409（请求冲突或超过受理截止）、422（参数无效）、429（入口或业务轮次限流、执行容量耗尽）和 503（依赖不可用）。执行受理后的模型失败通过终态快照返回脱敏错误。已开流时不能再改写 HTTP 状态。连接中断不改变执行终态。


## 实现验收

- 一次正常发送只产生一个浏览器 POST。分别记录点击到受理事件、点击到第一个正文增量的耗时，确认经本机 Nuxt 和 Spring 代理时能逐事件到达。
- 推理模型在正文前收到真实进度，重连可恢复当前阶段。进度只带阶段，普通模型可直接开始正文。
- 分别在首个受理事件前和正文输出中断开，恢复后使用同一执行 ID。模型调用次数保持为一，正文无缺失或重复。
- 关闭页面后，Spring 仍保存最终结果。终态到达浏览器时，网站会话详情已能读到同一结果。
- 匿名刷新和重复初始化复用当前会话。主动停止与模型失败保留已接收正文，其他身份无法访问或停止执行。
