# 站长数字人聊天接口契约

浏览器访问网站的 `/api/agent/v1`，网站访问 AI 服务的 `/internal/v1`。两端统一使用 camelCase JSON、UUID 标识和 UTC RFC 3339 时间戳。执行状态为 `accepted`（已受理）、`running`（运行中）、`completed`（已完成）、`failed`（失败）和 `interrupted`（已中断）。

## 网站接口

| 方法和路径 | 请求或响应 |
| --- | --- |
| GET /status | 读取网站是否开启数字人功能。 |
| POST /conversations | 登录用户新建会话。匿名身份首次调用时初始化当前会话，后续重复调用返回同一有效会话。返回 `conversationId`。 |
| GET /conversations | 按最近活动时间列出登录账号的会话，使用有界游标分页。 |
| GET /conversations/{conversationId} | 向会话所有者返回已保存的问答和当前执行状态。 |
| POST /conversations/import | 为登录账号导入 `importId` 和 `turns`。重复导入返回原会话。导入记录包含问题与回答，可信来源为空。 |
| POST /conversations/{conversationId}/turns | 接收 `requestId` 和 `question`。成功受理后以 HTTP 200 和 `text/event-stream` 在同一响应中返回受理事件、快照、正文增量和终态。 |
| GET /executions/{executionId} | 向执行所有者返回快照。 |
| GET /executions/{executionId}/events | 刷新或断线后，执行所有者续订已有执行。 |
| POST /executions/{executionId}/stop | 执行所有者停止执行并获取快照。 |

匿名浏览器在同源下保存当前 `conversationId` 和有限的本地问答。首次发送且尚无有效会话时才初始化，刷新时沿用原 ID。网站用服务端签发的不透明 Cookie 绑定匿名身份，为同一匿名身份原子地创建或复用唯一的有效会话。会话明确失效后才重新初始化。账号会话按登录用户隔离，资源不存在或不属于当前身份时均返回 404。导入成功前，浏览器保留匿名本地历史。网站从自身状态选取有界上下文，导入文本仅用于理解追问。可信来源由网站授权数据生成。

### 正常发送

```http
POST /api/agent/v1/conversations/aefdc98a-a5b8-40b4-b17d-282c02319418/turns
Content-Type: application/json
Accept: text/event-stream

{"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","question":"这个项目用了什么存储？"}
```

1. 浏览器点发送时先保存 `requestId`，立即显示用户消息和「正在发送」，再用 `fetch` 提交并读取同一响应的 SSE 流。
2. Spring 校验归属、参数与请求去重，记录本次提交的受理截止时间，再调用 Python `POST /executions`。Python 登记执行后返回编号，后台随后检查费用门禁并生成。
3. Spring 保存网站请求与 Python 执行的关联，马上在同一 POST 响应中发送首个 `accepted` 事件。浏览器保存 `executionId`，显示「正在生成」。已明确拒绝的请求在开流前返回普通 HTTP 错误。
4. Spring 订阅 Python 的已有执行，按顺序转发 `snapshot` 和 `delta`。即使 Python 在 Spring 连上前已生成正文，首个快照也能补齐。受理事件与正文增量及时刷新，代理不聚合缓冲。
5. Python 保存最终正文和终态。Spring 将正式消息和状态写入网站数据库，成功提交后才向浏览器发送 `terminal`。浏览器断开后，网站仍需跟进并保存最终结果。后台同步失败时，网站恢复后按已有执行 ID 查回补存。

### 刷新、断线和停止

- **已知执行 ID**。刷新后先读会话详情。还在生成时，按原 `executionId` 查询快照或续订 `GET /executions/{executionId}/events`。已保存的终态直接显示。续订只读取原执行。
- **尚未收到受理事件**。用原 `requestId` 和原问题重试同一 POST。Spring 用原截止时间和原输入核对受理状态，按标识查到原执行后在本次响应中接回同一条流。过截止且查明确认未受理，才告诉用户发送未成功。依赖暂不可用时保持待确认。相同标识提交不同问题返回 409。
- **主动停止**。仍使用 `POST /executions/{executionId}/stop`。Spring 核验归属后转发停止并保存结果。刷新和关闭页面不会触发停止。
- **模型或代理故障**。模型失败作为已保存的终态显示，保留已有正文。代理断线或网站暂时无法确认状态时，页面沿原编号恢复。收到 EOF 但没有 `terminal` 时按断线处理。用户明确重新生成或发出下一问时才使用新的 `requestId`。

## AI 服务接口

| 方法和路径 | 请求或响应 |
| --- | --- |
| POST /executions | 接收 `requestId`、`question`、`history` 和 `acceptBefore`。历史按顺序传入，每项包含 `role` 与 `text`，最多 12 项。返回 `executionId` 和 `status`。 |
| GET /executions/{executionId} | 返回执行快照。 |
| GET /executions?requestId={requestId} | 受理回包不明时，按原请求标识查询。 |
| GET /executions/{executionId}/events | 订阅已经持久化的执行。 |
| POST /executions/{executionId}/stop | 停止执行并返回快照。 |
| GET /settings | 返回生效修订和脱敏配置。 |
| PUT /settings | 使用 `expectedRevision` 更新可编辑设置。响应省略只写凭证。 |
| GET /health/live、GET /health/ready | 检查进程存活和依赖就绪，检查期间不会调用模型。 |

网站后端使用同一份站间 Bearer 凭证调用执行和配置接口，浏览器的会话与 OWNER 权限由网站校验。用相同标识和输入重试时，即使超过受理截止时间也返回原执行。超过截止时间的首次提交会被拒绝。上游结果不明时按同一请求标识核对。受理截止后若查不到执行，即可确定原请求无法再受理。

## SSE 事件和错误

网站的发送 POST 先发 `accepted`，携带 `requestId`、`executionId` 和当前 `status`。随后同续订接口一样发送 `snapshot`，客户端以其正文覆盖本地累计内容。网站续订 GET 和 Python 订阅 GET 都直接以 `snapshot` 开始。`delta` 的 `offset` 按 UTF-16 代码单元计算，与 Java 和 JavaScript 字符串索引一致。偏移不符时重新订阅并应用下一份快照。`sources` 替换来源列表，M1 阶段为空。`terminal` 携带最终快照。

以下为网站 POST 的成功响应示例。续订 GET 从 `snapshot` 开始。

```text
event: accepted
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"b904c054-b7d4-4588-8e36-e93a644831eb","status":"accepted"}

event: snapshot
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"b904c054-b7d4-4588-8e36-e93a644831eb","status":"running","text":"","sources":[],"error":null}

event: delta
data: {"offset":0,"text":"项目使用 PostgreSQL。"}

event: terminal
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"b904c054-b7d4-4588-8e36-e93a644831eb","status":"completed","text":"项目使用 PostgreSQL。","sources":[],"error":null}
```

主动停止保留已保存正文。停止已完成的执行时返回原完成快照。SSE 重连只读已有执行，不触发新生成。网站先提交最终消息和状态，再向浏览器发送终态。

AI 每小时分批清除结束超过 24 小时的临时正文，保留执行与请求标识、状态和费用账。网站保存正式聊天。

网站的普通 JSON 响应沿用 `code`、`message`、`data` 和 `details`。Python 错误响应结构为 `{"code":"REQUEST_CONFLICT","message":"请求标识已被使用。","requestId":"…"}`。常见状态包括 401（凭证无效）、404（资源不存在）、409（请求冲突或超过受理截止）、422（参数无效）、429（执行容量或预算耗尽）和 503（依赖不可用）。执行受理后的模型失败通过终态快照返回脱敏错误。已开流时不能再改写 HTTP 状态。连接中断不改变执行终态。


## 实现验收

- 一次正常发送只产生一个浏览器 POST。分别记录点击到受理事件、点击到第一个正文增量的耗时，确认经本机 Nuxt 和 Spring 代理时能逐事件到达。
- 分别在首个受理事件前和正文输出中断开，恢复后使用同一执行 ID。模型调用次数保持为一，正文无缺失或重复。
- 关闭页面后，Spring 仍保存最终结果。终态到达浏览器时，网站会话详情已能读到同一结果。
- 匿名刷新和重复初始化复用当前会话。主动停止与模型失败保留已接收正文，其他身份无法访问或停止执行。
