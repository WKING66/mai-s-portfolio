# 站长数字人聊天接口契约

浏览器访问网站的 `/api/agent/v1`，网站访问 AI 服务的 `/internal/v1`。两端统一使用 camelCase JSON、UUID 标识和 UTC RFC 3339 时间戳。执行状态为 `accepted`（已受理）、`running`（运行中）、`completed`（已完成）、`failed`（失败）和 `interrupted`（已中断）。

## 网站接口

| 方法和路径 | 请求或响应 |
| --- | --- |
| POST /conversations | 创建会话并返回 `conversationId`。 |
| GET /conversations | 按最近活动时间列出登录账号的会话，使用有界游标分页。 |
| GET /conversations/{conversationId} | 向会话所有者返回已保存的问答和当前执行状态。 |
| POST /conversations/import | 为登录账号导入 `importId` 和 `turns`。重复导入返回原会话。导入记录包含问题与回答，可信来源为空。 |
| POST /conversations/{conversationId}/turns | 接收 `requestId` 和 `question`。返回 `requestId`、`executionId` 和 `status`。 |
| GET /executions/{executionId} | 向执行所有者返回快照。 |
| GET /executions/{executionId}/events | 执行所有者订阅已有执行。 |
| POST /executions/{executionId}/stop | 执行所有者停止执行并获取快照。 |

匿名浏览器在同源下保留当前会话与有限的本地问答。网站用服务端签发的不透明 Cookie 绑定临时状态。账号会话按登录用户隔离，资源不存在或不属于当前身份时均返回 404。导入成功前，浏览器保留匿名本地历史。网站从自身状态选取有界上下文，导入文本仅用于理解追问。可信来源由网站授权数据生成。

提交示例：

```http
POST /api/agent/v1/conversations/aefdc98a-a5b8-40b4-b17d-282c02319418/turns
Content-Type: application/json

{"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","question":"这个项目用了什么存储？"}
```

网站先受理再开放订阅，返回 HTTP 202：

```json
{"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"b904c054-b7d4-4588-8e36-e93a644831eb","status":"accepted"}
```

提交重试时沿用原请求标识。同一所有者以同一标识提交不同内容会产生冲突。重连时使用已有执行标识。

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

订阅先发送 `snapshot`，客户端以其正文覆盖本地累计内容。`delta` 的 `offset` 按 UTF-16 代码单元计算，与 Java 和 JavaScript 字符串索引一致。偏移不符时重新订阅并应用下一份快照。`sources` 替换来源列表，M1 阶段为空。`terminal` 携带最终快照。

```text
event: snapshot
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"b904c054-b7d4-4588-8e36-e93a644831eb","status":"running","text":"","sources":[],"error":null}

event: delta
data: {"offset":0,"text":"项目使用 PostgreSQL。"}

event: terminal
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"b904c054-b7d4-4588-8e36-e93a644831eb","status":"completed","text":"项目使用 PostgreSQL。","sources":[],"error":null}
```

主动停止保留已保存正文。停止已完成的执行时返回原完成快照。SSE 重连只读已有执行，不触发新生成。网站先提交最终消息和状态，再向浏览器发送终态。

错误响应结构为 `{"code":"REQUEST_CONFLICT","message":"请求标识已被使用。","requestId":"…"}`。常见状态包括 401（凭证无效）、404（资源不存在）、409（请求冲突或超过受理截止）、422（参数无效）、429（执行容量或预算耗尽）和 503（依赖不可用）。执行受理后的失败通过终态快照返回脱敏错误。
