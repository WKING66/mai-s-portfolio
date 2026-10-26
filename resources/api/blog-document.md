# 博客与 Document API

本契约对应 V8 内容槽位模型及 V9 分类编码，替换旧 `version/documentVersionId` 语义；前端需同步后再做页面联调。前缀均为 `/api/v1`。既有会话、CSRF、OWNER 与错误响应规则不变，错误使用现有 ApiErrorCode。

## 写入请求

POST /blogs 创建：

```json
{
  "revision": 12,
  "requestId": "ed98059b-1ebe-4471-82f7-d68ba6030982",
  "slug": "my-article",
  "title": "文章标题",
  "summary": "摘要",
  "content": "Markdown，已保存资源为 resource://key，新增上传可用 asset://id",
  "tagIds": [1, 2],
  "allowDownload": true,
  "remoteRightsConfirmed": false
}
```

- 创建不要求 revision；两类更新必须带最近管理详情的 revision。
- requestId 必须为 UUID；同一次请求断线重试保持原 ID 和原输入，新的编辑或动作生成新 ID。
- tagIds 必传，空数组代表清空草稿标签；同时允许 TOPIC 与 TECH。标签不存在、重复选择均拒绝。
- slug 首次发布前可为空，发布前必须先保存；首次发布后（含下架）不可修改。
- 空标题/正文可以保存草稿，发布完整性属于业务校验。
- allowDownload 是博客级设置，保存后立即生效。标签随草稿保存，发布后才改变公开标签。

### 正文与属性分别保存

**请求收窄：原 PATCH /blogs/{id} 已改为只保存正文，不再写入附带的属性字段。没有 /content 别名或旧混合保存兼容分支，前后端必须同步部署。**

PATCH /blogs/{id}：

```json
{
  "revision": 12,
  "requestId": "ed98059b-1ebe-4471-82f7-d68ba6030982",
  "content": "仅正文；新增上传仍使用 asset://id，已有引用仍为 resource://key",
  "remoteRightsConfirmed": false
}
```

revision/requestId/content 必传，content 可为空；remoteRightsConfirmed 默认 false。不校验或修改 slug、标题、摘要、标签与下载设置。正文不变时不重新解析；只有明确授权重试失败网络资源时仍执行资源重试。

PATCH /blogs/{id}/properties：

```json
{
  "revision": 12,
  "requestId": "d95cd692-7bc4-45c3-a955-3a8838719539",
  "title": "文章标题",
  "summary": "摘要",
  "slug": "my-article",
  "tagIds": [1, 2],
  "allowDownload": true
}
```

这是完整属性表单，revision/requestId/tagIds/allowDownload 必传。title/summary 的 null 或空串均表示清空；slug 的 null/空白表示清空；tagIds=[] 清空。原 slug 完全不变时不校验历史格式或占用，真正修改时检查格式及其他文章占用并排除自身；已有正式正文时仍不允许修改 slug。

属性保存不解析正文、不访问上传/抓取流程。标题、摘要和标签仍保存到草稿，发布后才影响公开内容；allowDownload 立即生效，仅改变下载设置不凭空创建草稿。首次修改正文或草稿属性时如无草稿，只复制正式正文及原有引用关系，再定向修改草稿，正式正文不变。

两类保存都返回现有 BlogVo。前端只合并本次保存范围及共享 revision，不能用整个响应覆盖另一部分尚未保存的输入。同一文章的保存按顺序提交；任一写动作推进同一个 revision，旧请求返回 409，不自动重放覆盖。

**无改动：** 返回当前管理投影，不写库、不推进 revision、不改更新时间，也不登记新的 requestId。已经提交过的 requestId 仍检查原动作与输入；正文和属性的摘要包含不同动作类型，不能跨动作混用。

| 错误码 | HTTP | 含义及前端处理 |
| --- | --- | --- |
| BLOG_SLUG_INVALID | 422 | 实际修改的 slug 格式不合法，标记属性表单，不阻止正文保存 |
| BLOG_SLUG_IMMUTABLE | 422 | 首次发布后不能改变 slug |
| BLOG_SLUG_CONFLICT | 409 | slug 被其他文章占用，保留属性输入；不是修订冲突 |
| DOCUMENT_REVISION_CONFLICT | 409 | 其他保存或状态动作已推进 revision，保留两部分本地输入并提示重新读取 |
| REQUEST_ID_CONFLICT | 409 | 已提交的 requestId 换了动作或输入，不能当作成功重试 |
| VALIDATION_FAILED | 422 | 必填、长度等其他字段校验失败 |
| DATA_CONFLICT | 409 | 其他数据写入冲突，不应一律显示“slug 已存在” |

状态动作与删除：

```json
{"revision": 12, "requestId": "d95cd692-7bc4-45c3-a955-3a8838719539"}
```

## 接口与动作

| 方法与路径 | 权限 | 行为 |
| --- | --- | --- |
| GET /blogs?view=PUBLIC | 公开 | 列表只选正式正文且文章已上架 |
| GET /blogs?view=MANAGE | OWNER | 有草稿选草稿，否则选正式正文 |
| GET /blogs/by-slug/{slug} | 公开 | 下架/不存在返回 404 |
| GET /blogs/{id} | OWNER | 管理编辑投影 |
| POST /blogs | OWNER | 创建文档与唯一草稿，不发布 |
| PATCH /blogs/{id} | OWNER | 仅保存正文与资源，属性和正式正文不变 |
| PATCH /blogs/{id}/properties | OWNER | 保存草稿属性及下载设置，不解析正文或重新上传资源 |
| POST /blogs/{id}/publish | OWNER | 只提升已保存草稿，原子替换旧正式正文，成功后无草稿 |
| POST /blogs/{id}/unpublish | OWNER | 关闭公开入口，两个内容槽位均保留 |
| POST /blogs/{id}/restore | OWNER | 重新开放最近正式正文；不发布草稿、不改原发布时间 |
| DELETE /blogs/{id}/draft | OWNER | 必须已有正式正文；只丢弃草稿，正式正文和公开状态不变 |
| DELETE /blogs/{id} | OWNER | 已上架拒绝；已有正式正文且有草稿也拒绝；从未发布的草稿文章可直接整篇删除 |
| POST /blogs/imports | OWNER | 同步导入 Markdown 为草稿并记录来源/结果 |
| GET /blogs/imports?documentId={id}&page=1&size=12 | OWNER | 按文档筛选并分页，默认每页 12 条、最多 50 条，按记录 ID 倒序 |
| GET /blogs/imports/{taskId} | OWNER | 导入来源、SHA-256、状态和具体问题 |
| GET /blogs/{id}/export?view=PUBLIC&includeResources=false | 登录 | PUBLIC 须已上架且允许下载；MANAGE 须 OWNER |

整篇删除只解绑内容、资源和标签关系，不物理删除共享 Asset/OSS。导入追踪保留历史 documentId。删除成功后重试会返回 404，调用端可以按“已不存在”处理。

导入 multipart：`file` 为 UTF-8 .md；可选 `resources` 使用正文相对路径作为文件名；可选 `tagIds`；`remoteRightsConfirmed` 默认 false；`requestId` 必传 UUID。相同导入请求不重新执行，返回 409 及已有 taskId，调用端读取记录确认结果；失败后修正输入，以新 ID 创建新记录。

## 响应字段

| 字段 | 语义 |
| --- | --- |
| id / documentId | 博客身份 / 文档身份 |
| contentId | 当前读取内容的槽位 ID，不是历史版本；重复保存草稿时 ID 不变 |
| revision | 仅管理投影返回的统一写修订；公开为 null |
| stage | 当前所读内容为 DRAFT 或 PUBLISHED |
| hasDraft | 管理端是否另有工作草稿；公开为 null，不泄漏草稿状态 |
| hasPublished | 是否存在正式正文，不等于当前已上架 |
| status | PUBLISHED=上架，DRAFT=未公开（可能从未发布或已下架） |
| resourcesReady | 本次投影的全部资源是否就绪；false 不等于正文未保存 |
| tags | 当前槽位的标签 id/name/slug/kind；内容标签不依赖实时标签目录改名 |
| resources | 保留既有 key/type/status/assetId/resourceUrl/expiresAt/filename/contentType；管理另有 originalUri/errorMessage |
| previewIssue | 管理正文已保存但资源签名暂不可用时的提示 |

列表条目也使用 contentId/revision/stage/hasDraft/hasPublished，不再返回旧 version。

导入列表响应为 `{page, size, total, items}`，`items` 为本页记录，详情接口仍返回单条记录。分页参数共用 PageRequest，页码 1–10000、size 1–50，缺省 1/12；不再固定截取最近 50 条。所有分页共用 PageVO，博客/项目的 view 使用共享 ViewPageVO 扩展，JSON 字段不变。

导入追踪包含 id、documentId、requestId、originalFilename、sourceSize、sourceSha256、status、issuesJson 和时间。issuesJson 为 JSON 文本：资源问题是数组，整体失败为含 code/message 的对象。状态为 PROCESSING / SUCCEEDED / SUCCEEDED_WITH_ISSUES / FAILED；迁移旧未结束记录为 INTERRUPTED。进程中断可能留 PROCESSING，需要核查，不代表仍有后台任务运行。

## 前端适配要点

- 保存按钮、Ctrl+S、dirty 后空闲防抖保存调用同一个 PATCH；只接受对应请求的响应，保留保存期间产生的新编辑。
- 使用 revision 处理写冲突，不能用 contentId 判断草稿正文是否更新。公开渲染缓存可使用 contentId；管理缓存还应包含 revision 或具体内容。
- 保存成功后，依据 resourcesReady/previewIssue 区分正文已保存与资源问题。
- 明确区分“发布草稿”“恢复正式正文”“丢弃草稿”“整篇删除”，不要用 publish 代替 restore。
- 导入仍不发布；编辑从正式正文初始化时，实际保存才创建草稿。
- 409 后重新读取管理状态并保留本地编辑，不静默覆盖。仅最近一次已提交的编辑/状态动作可用相同 ID/输入安全重试；如果中间已发生其他写操作，旧请求返回 409，应重新读取并保留本地编辑，不能盲目换 ID 重放旧内容。创建按创建请求标识找回已有文章，导入按导入记录去重；不承诺重放任意历史请求的首次响应，也不新增请求日志表。
- 资源文件名来自 Asset.filename；普通资源继续浏览器直连签名 URL。
- 未勾选资源或文章无资源时导出“标题.md”，其中资源是远程签名链接；勾选且有资源时“标题.zip”，包含“标题.md”和平铺的 resources/。现有选择逻辑不改变。

## 标签目录

GET /tags：可选 kind=TOPIC 或 TECH，不传返回两类；项目调用端继续指定 TECH。旧技术标签内部 Provider 保持原接口。标签选项和首页技术栈共用只读目录缓存，默认一分钟；创建标签事务提交后本实例立即清除，回滚不清除。其他实例或直接改库最多等待 TTL；配置 `portfolio.taxonomy.cache-ttl=PT0S` 可关闭缓存。保存文章时的标签校验始终批量查库，不依赖此展示缓存。

POST /tags（OWNER）：

```json
{"kind":"TOPIC","name":"数据结构","slug":"data-structures","group":null,"logoKey":null}
```

TECH 必须指定 LANGUAGE/FRAMEWORK/TOOL/INFRA/DATA 分组，TOPIC 不指定技术分组。类别内名称及全站 slug 冲突返回 409。本轮不新增标签删除、RAG 或后台任务。

## 数据库存储编码

HTTP 中的 stage、标签 kind、导入 status 和资产 usage 名称不变。数据库统一 SMALLINT：
stage：0=DRAFT、1=PUBLISHED；kind：0=TOPIC、1=TECH（与 tag.kind 相同）；
导入 status：0=PROCESSING、1=SUCCEEDED、2=SUCCEEDED_WITH_ISSUES、3=FAILED、4=INTERRUPTED；
usage：0=AVATAR、1=IMAGE、2=ATTACHMENT。升级时由 V9 转换已有记录，不删除内容或引用。

## 查询装配

公开详情先 JOIN blog_post、document、document_content 读取唯一正式正文，再分别读取这份 contentId 的资源和标签，最后一次读取图片与附件 Asset 并生成 URL。含两类资源时最多 4 次查询，与资源数量无关；不是把正文和全部一对多关系拼成一条大 JOIN。正文及关系仍处于同一只读事务视图，未发布草稿不参与公开读取；签名 URL 是临时结果，不保存到正文或标签缓存中。
