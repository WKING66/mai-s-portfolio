# 前后端 HTTP 合同（Phase 1）

Nuxt 与 Spring Boot 是独立进程。浏览器以同源 `/api/v1` 访问后端；本地由 Nuxt 开发代理，正式反向代理方式待部署阶段决定。Nuxt SSR 调用相同的公开数据合同，不能在前端另设“草稿是否公开”的判断。所有 JSON 使用 UTF-8，时间为 ISO 8601 UTC，列表有明确分页上限。具体字段类型以 [数据模型](../data-model.md) 为准。

## 身份与通用规则

- 管理端采用站点主人会话 Cookie（HttpOnly；上线 HTTPS 后 Secure）和 CSRF 令牌。`GET /api/v1/admin/session` 用于判断登录状态并取得后续写操作需要的 CSRF 信息；`GET /api/v1/admin/session/challenge` 签发短时、一次性的 RSA-OAEP SHA-256 登录公钥；`POST /api/v1/admin/session` 仅接受 `username`、`challengeId`、`encryptedPassword`，不接受明文密码字段；`DELETE` 登出。私钥只留在当前后端进程内存，挑战提交后立即消费，重放/过期需重新获取；首版单实例运行。浏览器在本机安全上下文中用 Web Crypto 加密密码，公网 HTTP 下此机制不能防公钥/脚本替换，正式部署仍需 HTTPS。`user_account.type` 用 TINYINT 区分 OWNER(0) 与 NORMAL(1)，管理会话只授予 `type = 0` 且账号启用的站主；普通账号不能取得管理权限。首版无访客注册/登录接口。密码不出现在 URL、日志或仓库中。
- `/api/v1/admin/**` 的数据写入、预览、草稿读取与主人导出均需已登录；公开读取不需登录。任何媒体/导出权限由 Spring Boot 在**每次请求**重新检查。
- 除文件字节下载外，JSON 响应统一为 `{ "code": "...", "message": "...", "data": ..., "details": [...] }`。成功时 `code` 为 `OK`、`data` 为对应业务对象、`details` 为空数组；失败时 `data` 为 `null`，保留实际 HTTP 错误状态与稳定业务 `code`。`details` 可标出缺图路径或远程 URL 的安全摘要，不能回显凭据。前后端及 OpenAPI 必须使用同一结构。
- `401` 未登录，`403` 无权限，`404` 不存在或匿名不可见，`409` 版本冲突，`413` 文件过大，`422` 输入/归档校验失败。未发布内容对匿名读者统一按 `404` 处理，避免泄漏存在性。
- 所有请求由后端服务先校验必填、格式、状态码、唯一性、关联目标和权限，返回上述稳定错误；数据库唯一索引/外键只维护完整性，不能把原始 SQL 异常作为正常响应或代替服务端校验。
- 所有外部展示链接只接受 HTTP(S)；邮箱由服务端生成合法 `mailto:`。项目和文章 ID/slug 永不复用给另一项内容。
- 数据库中的所有主键是自增数值，媒体 ID 也可被枚举；接口仍须每次核验媒体、草稿和导出权限。数据库的 TINYINT 状态/类别码由后端映射为 API 中可读的名称，不把数值编码暴露为前端业务判断依据。
- 后端本地开发配置提供 `/swagger-ui.html` 与 `/v3/api-docs`（OpenAPI JSON）；公开/管理接口均记录请求、响应、认证和主要错误。文档必须随控制器同步验证；正式环境默认关闭这些文档端点，除非以后明确配置仅授权维护者可访问。

## 公开读取

| 方法与路径 | 返回/行为 |
| --- | --- |
| `GET /api/v1/public/profile` | 已批准的昵称、定位、介绍、技术栈、可用联系入口及头像/简历可用状态；简历空时无下载地址。 |
| `GET /api/v1/public/projects?page=&size=` | 仅已发布卡片；标题、摘要、贡献、标签、封面媒体 ID 和可选代码/演示 URL；不提供项目站内详情。 |
| `GET /api/v1/public/articles?page=&size=&tag=` | 仅已发布文章摘要、主题、标签、日期、阅读时长、规范路径。 |
| `GET /api/v1/public/articles/{slug}` | 已发布文章 Markdown 渲染结果与标题/作者/日期等元数据；输出 HTML 经安全处理，图片仅指向站内媒体地址。 |
| `GET /api/v1/media/{id}` | 检查所属文章/项目/个人资料是否公开，或请求者是否站点主人；返回图片字节。草稿媒体匿名 `404`。首版使用保守缓存策略，撤稿后不得由站点缓存继续公开。 |
| `GET /api/v1/public/articles/{slug}/export?format=md\|docx\|pdf` | 仅文章已发布且允许访客下载时生成附件；否则 `404/403`，不因知道 URL 绕过许可。 |

Nuxt 公开路由为 `/`、`/projects`、`/blog`、`/blog/{slug}`。SSR 在初始 HTML 中输出主体正文及路由对应的 title/description/canonical；`/sitemap.xml` 只含已发布路由，项目因无独立详情页不生成项目详情 URL；文章详情按可见数据生成 Article/BlogPosting 结构化数据。`/admin/**`、草稿和预览页不进入站点地图并标记不可索引。

## 管理端资料与项目

| 方法与路径 | 输入与后置条件 |
| --- | --- |
| `GET/PATCH /api/v1/admin/profile` | 查看/编辑获准公开的资料与入口；空简历不生成下载 URL。头像上传走媒体接口。 |
| `GET/POST /api/v1/admin/projects` | 列表包含草稿；创建项目默认 `DRAFT`。输入为标题、摘要、本人贡献、标签及可选外链。 |
| `GET/PATCH /api/v1/admin/projects/{id}` | 查看/编辑卡片资料，版本冲突返回 `409`；不接收源码目录。 |
| `POST /api/v1/admin/projects/{id}/publish` | 必填资料与媒体就绪后转 `PUBLISHED`；否则 `422`，指出缺失字段/图片。 |
| `POST /api/v1/admin/projects/{id}/unpublish` | 转回 `DRAFT`；公开列表、媒体及未来 Agent 数据源立即失效。 |

首版没有访客项目写入和站内项目详情接口。删除不是主要生命周期操作；先保留下架功能，防止误删资料。

## 管理端文章、导入和导出

| 方法与路径 | 输入与后置条件 |
| --- | --- |
| `GET/POST /api/v1/admin/articles` | 管理列表含草稿；新建文章默认 Markdown `DRAFT`，下载许可默认 `false`。 |
| `GET/PATCH /api/v1/admin/articles/{id}` | 返回/更新 Markdown 源文、摘要、标签和版本；版本不匹配 `409`，不静默覆盖。 |
| `POST /api/v1/admin/articles/{id}/preview` | 用当前 Markdown 返回安全预览与未归档图片问题；不改变发布状态。 |
| `POST /api/v1/admin/articles/{id}/publish` | 仅在正文、元数据和所有引用图片就绪后发布。 |
| `POST /api/v1/admin/articles/{id}/unpublish` | 转草稿，公开页/导出/图片访问失效；内容保留。 |
| `PATCH /api/v1/admin/articles/{id}/download-policy` | `{ "allowed": true/false }`；访客许可默认关闭，撤销立即生效。 |
| `GET /api/v1/admin/articles/{id}/export?format=md\|docx\|pdf` | 站点主人对自己的草稿与已发布文章均可导出，不受访客许可影响。 |
| `POST /api/v1/admin/imports/markdown` | 见下方导入约定；返回可预览的草稿与逐项问题，**绝不自动发布**。 |

### Markdown 导入约定

输入为 `multipart/form-data` 的 `bundle`，并包含布尔字段 `rightsConfirmed`；只要文中含网络图片，该字段必须为 `true`，否则相应引用标记为未归档并保持草稿：

1. 不引用本地图片或仅有网络图片时，`bundle` 可以是单个 `.md`。
2. 引用本地图片时，`bundle` 是 ZIP 传输包，内含**恰好一篇 `.md`**及按原相对路径排列的图片文件。ZIP 只是 Markdown 与图片的搬运容器，不表示支持 ZIP 文档、Word 或 PDF 导入。
3. 网络图片另需站点主人确认其有权使用且同意归档；后端逐张安全下载、验证、存入 OSS，并把成功引用重写成站内 `/api/v1/media/{id}`。不允许在发布结果中继续使用原始第三方图片 URL。
4. 本地路径必须以包内 `.md` 目录为基准解析，拒绝绝对路径、`..` 越界、同名冲突、超限与不支持的类型；网络地址拒绝内网目标、危险跳转、超时与伪装图片。所有问题写入 `issues[]`（引用位置、原因、建议），生成的文章始终为草稿；问题未解决不能发布。
5. 示例响应：`{ "code": "OK", "message": "成功", "data": { "articleId": 123, "status": "DRAFT", "issues": [{ "reference": "images/missing.png", "code": "IMAGE_MISSING", "message": "未提供引用的图片" }] }, "details": [] }`。预览对未归档图片显示警告占位，不让浏览器自行去第三方地址加载。

### 图片写入

`POST /api/v1/admin/profile/media`、`POST /api/v1/admin/articles/{id}/media` 和 `POST /api/v1/admin/projects/{id}/media` 接收站点主人上传图片；文章及项目另有对应的 `.../media/remote`，接收 `{ "url": "https://…", "rightsConfirmed": true }`。返回媒体 ID、站内稳定 URL 和可用状态。服务端限制文件类型、真实内容、字节、像素与对象键；外部网络抓取不得由访客触发。原始 OSS 凭据与对象键不返回浏览器。

## 跨进程与未来版本约束

- Nuxt SSR 只消费公开 API；后台页面通过管理 API 交互。开发与正式环境都保持浏览器视角的同源 `/api/v1`，防止用放宽 CORS 或关闭 CSRF 解决集成问题。
- 第二版 Agent 尚无公开合同。未来若实现，仅能读取 `PUBLISHED` 的 profile/project/article 快照（稳定 ID、更新时间、可引用链接），不得读取草稿、未授权下载文件或原始私有 OSS 对象。
- 不为第二版预建 Redis/Caffeine 缓存路由或向量接口；出现真实性能证据后另立规格。
