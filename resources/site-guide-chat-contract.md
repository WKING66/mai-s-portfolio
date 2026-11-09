# Site guide chat contract

The browser calls the website under `/api/agent/v1`. The website calls the AI service under `/internal/v1`. Both use camelCase JSON, UUID identifiers and UTC RFC 3339 timestamps. Execution states are `accepted`, `running`, `completed`, `failed`, and `interrupted`.

## Website

| Method and path | Request or result |
| --- | --- |
| POST /conversations | Creates a conversation and returns `conversationId`. |
| GET /conversations | Lists the signed-in account's conversations, newest activity first, with a bounded cursor. |
| GET /conversations/{conversationId} | Returns saved turns and current execution state for the owner. |
| POST /conversations/import | Imports `importId` and `turns` for the signed-in account. Repeating the same import returns the original conversation. Imported turns contain question and answer text and have empty trusted sources. |
| POST /conversations/{conversationId}/turns | Accepts `requestId` and `question`. Returns `requestId`, `executionId` and `status`. |
| GET /executions/{executionId} | Returns an execution snapshot for the owner. |
| GET /executions/{executionId}/events | Subscribes to the existing execution for the owner. |
| POST /executions/{executionId}/stop | Stops the execution for the owner and returns its snapshot. |

A same-origin anonymous browser retains its current conversation and a limited local transcript. The website binds temporary state to a server-issued opaque cookie. Account conversations are scoped to the authenticated owner. A foreign or missing resource returns 404. The browser keeps anonymous local history until import succeeds. The website obtains bounded conversational history from its own state. Imported text may supply context for a follow-up question. Trusted source metadata comes from the website's authorized sources.

Example submission:

```http
POST /api/agent/v1/conversations/aefdc98a-a5b8-40b4-b17d-282c02319418/turns
Content-Type: application/json

{"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","question":"What did the project use for storage?"}
```

The website accepts a turn before it exposes the subscription and returns HTTP 202 with:

```json
{"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"b904c054-b7d4-4588-8e36-e93a644831eb","status":"accepted"}
```

Keep the request identifier across submission retries. Reusing it with different content for the same owner conflicts. Reconnect using the existing execution identifier.

## AI service

| Method and path | Request or result |
| --- | --- |
| POST /executions | Receives `requestId`, `question`, `history` and `acceptBefore`. History is an ordered list of role/text pairs, bounded to 12 entries. Returns `executionId` and `status`. |
| GET /executions/{executionId} | Returns an execution snapshot. |
| GET /executions?requestId={requestId} | Looks up an accepted execution after an uncertain submission response. |
| GET /executions/{executionId}/events | Subscribes to a persisted execution. |
| POST /executions/{executionId}/stop | Stops an execution and returns its snapshot. |
| GET /settings | Returns the active revision and redacted configuration. |
| PUT /settings | Replaces editable settings using `expectedRevision`. Write-only credentials are omitted from responses. |
| GET /health/live, GET /health/ready | Checks process health and dependency readiness without invoking a model. |

Execution and settings use separate Bearer credentials. A repeat with the same request identifier and inputs returns the original execution, including after the acceptance deadline. A new request after the deadline is rejected. An ambiguous upstream result is reconciled by querying the same request identifier. Once no new acceptance is possible, a missing execution resolves the ambiguity.

## Events and errors

Both SSE endpoints emit a snapshot first. Its text replaces the locally accumulated answer. A delta carries the offset at which its text starts, measured in UTF-16 code units, matching Java and JavaScript string indexing. The sources event replaces the current source list. An unexpected offset is resolved by reconnecting and applying the next snapshot. Terminal events carry the final persisted snapshot. In M1, sources are empty.

```text
event: snapshot
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"b904c054-b7d4-4588-8e36-e93a644831eb","status":"running","text":"","sources":[],"error":null}

event: delta
data: {"offset":0,"text":"The project used PostgreSQL."}

event: terminal
data: {"requestId":"5ee922be-e132-4efe-917b-d05fca53d836","executionId":"b904c054-b7d4-4588-8e36-e93a644831eb","status":"completed","text":"The project used PostgreSQL.","sources":[],"error":null}
```

A stopped execution preserves the text saved so far. Stopping a completed execution returns the completed snapshot. SSE reconnects read existing state and incur no new generation. Website consumers receive a terminal state only after the final message and state commit.

Errors use `{"code":"REQUEST_CONFLICT","message":"The request identifier is already in use.","requestId":"…"}`. Common responses include 401 for invalid credentials, 404 for an unknown resource, 409 for conflict or expired acceptance, 422 for invalid input, 429 for execution capacity or budget exhaustion, and 503 for unavailable dependencies. Once an execution is accepted, later failures appear as a sanitized error in its terminal snapshot.
