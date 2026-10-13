# 博客 Document 首轮实现与可维护性审查

日期：2026-10-08。功能分支：`feature/blog-document-lifecycle`。父分支：`feature/auth-registration-route-fix`，基点 `b58fc8a`。

本轮在独立工作树实施，维护者原工作区的未提交文件、开发配置和凭据没有纳入。提交后停止等待审核，不合并、不推送。

完整阅读了外部参考材料 `F:/download/document-abstraction-design.md`。实施范围以维护者本轮要求为准：第一轮打通 Markdown 博客生命周期；DOCX/PDF 后续按真实格式需求补充；RAG 属于第二阶段。导出以最后的选择规则为准：正文或无资源返回 MD，包含资源且有资源返回 ZIP。

## 1. 修改前真实链路与最小方案

修改前 Blog Maven 模块和 Java 包主要是骨架；没有实际 Blog Controller、Service、Mapper、解析器、导出器或编辑页面。首页博客区域是占位展示，不能把数据库里已经有表视为功能已完成。

数据库基线已有 `document → document_version → document_version_asset` 和 `blog_post`。文件域已有统一上传接口、图片校验、Asset 状态、存储适配器以及临时访问链接查询。没有可复用的博客正文保存或导出实现。

最小实施采用现有表及不可变版本，不重新建另一套内容库。先复用文件能力，再实现文档模型、Markdown 导入、资源归档、草稿保存、公开投影和导出；没有跨到 RAG。

修改前的职责清单：

| 当前能力 | 实际职责 | 重复或缺口 | 目标处理 | 收敛位置 | 理由 |
|---|---|---|---|---|---|
| POST /api/v1/assets | HTTP 上传、校验、资产创建 | 校验入口绑定 Multipart，内部导入无法直接复用 | 提取可信文件入口，HTTP 委托 | AssetFileService → AssetUploadComponent | 博客导入和浏览器上传执行同一套校验及资产生命周期 |
| ObjectStorage | 写入、读取、临时链接、删除 | 可复用，不属于博客领域 | 只由资产域调用 | 现有存储适配器 | 博客不依赖 OSS SDK 或存储键规则 |
| document / document_version | 当前版本及正文快照 | 只有数据库结构 | 实现统一 Document 生命周期 | DocumentService | 导入来源不决定保存、展示或发布方式 |
| document_version_asset | 版本中的文件引用 | 无稳定正文键、缺少失败资源表达 | 增量迁移，媒体关联允许为空 | DocumentResource | 保留失败来源、类型和状态，而非静默删图 |
| blog_post | 发布状态、公开版本、slug、下载策略 | 只有数据库结构 | 实现一套博客业务服务 | BlogService | 管理和公开由权限及版本投影区分 |
| 博客首页占位 | 无实际文章查询 | 功能缺失 | 接入公开文章列表 | BlogJournal / BlogCards | 不复制后台查询业务 |
| 博客导出 | 无实现 | 功能缺失 | 受保护动态导出 | DocumentExportService | 普通图片仍由 OSS 传输 |

## 2. 新链路与业务规则

```text
Markdown / 编辑正文
    → ParsedDocument（仅导入）
    → DocumentResourceResolver
    → PreparedDocument（规范化正文 + 引用 + 本次新资产）
    → DocumentService（不可变版本）
    → BlogService（草稿、发布、下载策略）
    → DocumentRenderer（派生 HTML、临时资源 URL）
    → Nuxt
```

稳定核心是规范化正文、稳定资源引用、不可变版本和显式发布。已知变化是输入文档格式、资源来源以及导出组合。格式差异放在 Parser；资源来源差异放在解析结果和资源准备组件；资源校验及存储事实仍由 Asset 负责。没有将业务判断放到 DTO、Mapper 或 Controller。

草稿可不完整；发布必须有 slug、标题、正文，全部资源已就绪。首次发布后 slug 固定，避免破坏公开链接。保存新草稿不改公开版本，发布时才切换公开指针。下架只影响列表、详情和公开导出，不删除资源或要求已有 URL 立即失效。

## 3. 当前 Document 模型

`Document` 包含 documentId、versionId、version、lockVersion、title、summary、content 和 resources。content 是统一 Markdown，资源位置是 `resource://key`；签名 URL 和 HTML 是派生值，不写回正文。

`DocumentResource` 包含：

- key：版本内稳定引用，后续编辑保持同一资源键；
- type：IMAGE / ATTACHMENT；
- source：EMBEDDED / LOCAL / REMOTE；
- originalUri：来源或原相对路径，仅管理投影可见；
- assetId：已归档文件关联；
- status：READY / FETCH_FAILED / INVALID。

非就绪资源允许 assetId 为空，构造边界校验状态与关联是否一致。没有持久化错误堆栈、云厂商响应或凭据；网络来源隐藏 userinfo 和常见鉴权查询参数。需要重新抓取被脱敏的受保护来源时，编辑者应重新提供当前有效地址。

## 4. Blog 与 Document 的关系

Blog 一对一关联 Document；Document 持有当前版本指针，Blog 持有已发布版本指针。正文只在不可变 DocumentVersion 中保存。Blog 不保存第二份正文，也不知道原格式如何解析。

编辑和发布使用文档 lockVersion 做数据库条件更新，冲突返回项目统一 DATA_CONFLICT。锁修订号与内容版本号不必连续相同：发布/下架也会推进锁。读取编辑快照采用一致事务快照，避免旧正文配上新锁版本。

列表批量读取选定版本的标题与摘要，不对每篇文章读取整份正文和资源。

## 5. DocumentResource 与 Asset 的关系

DocumentResource 表达“这个版本引用什么、从哪里得到、当前准备结果”。Asset 表达“文件在哪、谁上传、类型、大小、校验和、存储状态”。二者没有复制存储键或签名 URL。

V5 在现有 `document_version_asset` 上增加稳定键、资源类型、来源及状态，使失败引用可保存。新增状态字段遵循既有 SMALLINT 约定并添加注释；业务校验在服务中集中维护，FK 和唯一约束负责数据关联与并发底线。

文件域职责：

- AssetService：HTTP 权限、Multipart 有界读取、返回模型；
- AssetFileService：HTTP 与导入共享的内容校验/上传入口，以及内部导出读取和新资产补偿；
- AssetUploadComponent：现有 PENDING → 存储写入 → READY 生命周期，不复制；
- AssetQueryService：现有就绪元数据和临时访问链接查询，不复制。

## 6. 文档导入完整流程

OWNER + CSRF → POST /blogs/imports → 有界读取 UTF-8 .md 与可选本地资源 → MarkdownDocumentParser → ParsedDocument → 资源准备 → 统一文件服务 → 同一事务创建 DocumentVersion、资源引用、Blog 草稿。

Parser 只解析，不访问数据库、OSS 或当前账号。严格检查 UTF-8、文件后缀、空文件、正文上限；BOM 和换行规范化，标题优先取第一个一级标题。DOCX/PDF 当前明确返回不支持格式，不通过空实现或原文件 URL 冒充正文。

本地文件按正文相对路径映射，支持空格及 UTF-8 百分号编码。禁止绝对路径、越界路径、反斜杠和非法编码；不从服务器文件系统读取任何导入路径。文件夹选择以 Markdown 所在目录为根，前端去掉所选目录名后保留内部层级。

当前默认：正文 2 MiB、最多 50 个资源、导入文件总量 64 MiB。通过 portfolio.document 配置，验证配置范围。已有 Multipart 总请求上限仍有效。

## 7. 图片和附件流程、失败路径

编辑器图片与附件都使用现有 POST /assets，插入 asset://id；保存时验证文件状态、用途和上传账号，再转换为稳定 resource://key。无 BlogImageUploadService 或附件 OSS 副本。

导入的本地/内嵌资源统一输出 DocumentInputResource，进入相同 AssetFileService。EMBEDDED 是资源来源语义，当前没有伪造 DOCX 内嵌解析器。

网络图片需既有正式需求规定的使用权确认。抓取仅支持 HTTP(S)、公开网络地址和受支持图片类型；校验发生在实际连接所用 DNS 解析器内，逐跳检查重定向。禁止 userinfo、非常规端口、私网/回环/链路本地/保留地址，不继承 Cookie、凭据或系统代理。数量、字节、连接、读超时、重定向次数及整份文档抓取预算都有边界。操作系统 DNS 耗时仍依赖部署网络环境。

代码块中的示例地址和普通网页链接不归档。HTTP 普通链接不猜测成附件；显式上传的附件、本地文件引用才进入资源生命周期。

下载、校验或缺失文件不会默默丢失：草稿保留失败资源，页面列出原引用，发布/完整导出被拒绝。远程失败引用在再次确认后可重试，保持原 key；本地失败可上传正确文件后替换引用或移除。

资源 IO 在数据库短事务外完成。保存失败时只补偿本次新资产；先尝试删除未绑定元数据，FK 会阻止删除已绑定文件，成功后才删 OSS。补偿失败记录安全标识与异常类别，不掩盖原错误。

签名服务失败不把已提交草稿报告成保存失败：管理投影返回 canonical content、版本和 previewIssue，前端 Toast 提示稍后重新读取。公开读取和导出仍明确失败，不返回假成功资源。

## 8. 博客展示完整流程

公开列表只查已发布 Blog；详情选择 publishedVersionId → Document → 批量解析 Asset 引用 → 私有 Bucket 临时签名 URL → 安全 Markdown HTML → Nuxt SSR → 浏览器直接访问 OSS。

公开投影不暴露 canonical resource:// 正文或原始来源；编辑投影保留规范化正文。HTML 转义原始 HTML，并清理危险 URL，不允许运行导入正文的脚本。

前端复用已有资源地址过期检测和刷新能力。刷新编辑预览不覆盖未保存输入；若版本已变化，提示重新读取。公开文章过期的客户端缓存不复用。正文资源链接失效只重新获取投影，不重新解析原文件。

## 9. 导出完整流程

登录及账号状态检查 → Blog 下载权限及版本选择 → Document 快照 → DocumentExportService 准备响应 → StreamingResponseBody → 浏览器下载。

| includeResources | 文档有资源 | 结果 |
|---|---|---|
| false | 任意 | .md，引用本次生成的临时访问链接 |
| true | 否 | .md |
| true | 是 | .zip：Markdown + 图片/附件，正文引用 ZIP 内相对路径 |

PUBLIC 导出需已发布并允许访客下载；MANAGE 需 OWNER，可导出已保存草稿。不存在未登录导出接口。权限和快照在异步输出前确定，输出回调不依赖请求线程的会话上下文。

ZIP 按资源顺序打开、复制、关闭，每次固定大小缓冲，不把全部资源加载到 JVM。文件名防路径穿越和常见 Windows 文件名冲突。输出失败关闭当前资源并释放压缩器，不写完整 ZIP 中央目录，避免损坏包被误认为成功导出。

复用 Boot 的 MVC 异步执行器，配置有限线程/队列和导出请求超时；没有另建异步业务状态机。[框架依据](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html)

普通图片没有 GET 二进制代理，也不共用导出流。

## 10. API 与清理结果

所有博客接口位于同一个 BlogController，一套 BlogService；公开/管理是投影及权限属性：

| 接口 | 功能 | 权限 |
|---|---|---|
| GET /api/v1/blogs?view=PUBLIC | 发布版本分页卡片 | 公开 |
| GET /api/v1/blogs?view=MANAGE | 当前草稿分页卡片 | OWNER |
| GET /api/v1/blogs/by-slug/{slug} | 公开正文及临时资源地址 | 公开 |
| GET /api/v1/blogs/{id} | 编辑快照、当前版本 | OWNER |
| POST /api/v1/blogs | 新建草稿 | OWNER + CSRF |
| PATCH /api/v1/blogs/{id} | 保存不可变新版本 | OWNER + CSRF |
| POST /api/v1/blogs/imports | 导入为草稿 | OWNER + CSRF |
| POST /api/v1/blogs/{id}/publish | 发布当前快照 | OWNER + CSRF |
| POST /api/v1/blogs/{id}/unpublish | 下架 | OWNER + CSRF |
| GET /api/v1/blogs/{id}/export | 正文 MD / 含资源 ZIP | 登录，按 view 追加权限 |

GET /blogs 的两种投影是同一个 API 操作；共九个 HTTP 操作。

本轮合并 AssetService 中图片/附件的生产校验和上传构造到 AssetFileService，原内联实现已经移除。博客模块移除对 storage-starter 的直接依赖和未使用的 api-log-starter 依赖；没有原文件存储逻辑、重复 OSS 上传器、临时兼容接口、DOCX/PDF/RAG 占位实现。

此前父分支已经删除的头像/封面/public media 字节代理，本轮没有重新添加，也不算作本轮删除成果。Blog 本来没有旧 Service/API 可删除。原 Java package-info 保留为现有包说明，不代表双实现。

AccountProfile 与 SiteProfile、Asset 与 DocumentResource、Blog 发布与 Document 内容版本仍独立，因为它们表达不同事实。本轮没有复制 AdminBlogService/PublicBlogService；目录继续是同模块的一套 controller/service/mapper 层。

## 11. 前端同步内容

新增 /admin/blogs、/admin/blogs/new、/admin/blogs/{id}；列表、导入、统一编辑、上传引用、手动保存、发布/下架、草稿导出已接通。后台导航增加博客入口。

新增 /blog 和 /blog/{slug}，公开列表有分页，详情 SSR 展示和登录下载；首页占位区域替换成近期博客。通知复用既有 Nuxt UI 顶部居中函数式 Toast。

下载和 JSON 请求共用现有鉴权流程：401/权限撤销更新会话，旧请求不自动重放；注销/换账号期间到达的旧下载响应拒绝交给页面。没有第二套会话缓存、双请求或旧 API fallback。

## 12. 主动推迟的能力与第二阶段边界

未实现 Chunk、Embedding、pgvector 写入、知识库同步、RAG Pipeline/MQ、Agent 检索、KnowledgeDocument 转换或索引状态机；没有为本轮主动扩展既有 Agent 模块。

它们没有当前博客调用方，会把本轮同步文档生命周期扩大成第二套异步状态。第二阶段从 DocumentService.read(documentId, versionId) 返回的规范化 Document 与稳定资源语义消费，不重解析原 DOCX/PDF/Markdown，不从短期 URL 或未来向量反推博客正文。

当前已支持的扩展：

- 新输入格式实现 DocumentParser，提供 ParsedDocument 和 DocumentInputResource，再增加实际格式选择和对应 origin；Blog、资源准备、持久化及发布主流程不用复制。当前只注入唯一 Markdown 实现，没有提前建 Registry/Factory。
- 新博客图片/附件/项目截图/头像继续复用 AssetFileService 与已有 AssetUsage；业务绑定在相应业务服务完成。新增真正不同校验规则时集中扩充文件域，而不是复制 Controller/Service。
- 导出组合由当前 DocumentExportService 承载；DOCX/PDF 导出到实际实施时再增加真实导出器，不提前搭空扩展框架。
- 本地/内嵌/远程来源是同一 Resource 模型，不复制文件存储生命周期。

## 13. Maintainability Review 与技术债务

| 检查 | 结论与证据 |
|---|---|
| 同类业务增加要改多少位置 | 普通资源用途不新增上传 Controller/Service；新格式只新增实际 Parser、格式选择/origin 和前端选择描述，不复制 Blog/Repository/资源归档主流程 |
| 替换外部依赖影响 | 替换 OSS 适配器影响存储模块及部署配置；博客通过 asset-provider 调用，无 OSS SDK/存储键依赖 |
| 重复业务规则 | 上传内容校验在文件域，引用绑定在文档准备组件，发布/下载策略在 BlogService，版本/CAS 在 DocumentService；没有 admin/public 双服务 |
| 为测试凑分支 | 测试使用真实 Flyway/MyBatis/事务；没有生产 fixture ID、特殊文件名或测试账户分支 |
| Source of Truth | Blog 发布及策略、DocumentVersion 正文、DocumentResource 引用、Asset 文件事实明确；HTML/签名 URL 只派生 |
| 失败路径 | 缺失/损坏资源、未知格式、UTF-8/大小/数量/路径/DNS 限制、并发冲突、DB 回滚、补偿失败、签名失败、导出断流均有明确行为 |
| 配置/异常/日志 | 使用现有配置绑定、ApiException/ErrorCode；日志只记业务 ID 与异常类别，不打印正文/凭据/签名地址；状态字段遵循 SMALLINT |
| 是否迫使复制粘贴 | 新格式共用解析结果之后的主流程；新资源用途共用文件入口；当前没有发现新增的场景复制或职责不明区域 |

已知边界/债务：

- 当前只有 Markdown 解析和 MD/ZIP 导出。DOCX/PDF 尚未实现，不能称三格式全部完成。
- 编辑器是基础 Markdown 文本编辑及已保存预览；标签/主题、封面选择、历史版本 UI、富文本交互没有在本轮加入。
- 历史版本资源保留，不自动删旧文件。补偿是 best effort，跨 DB/OSS 无分布式事务；失败资产或删除失败的对象需后续统一运营清理，不加 MQ/复杂状态机伪装已解决。
- .md 的签名资源链接会到期；ZIP 提供离线资源。浏览器下载使用 Blob，客户端仍会占用完整下载大小的内存，后端流式不会改变这一浏览器行为。
- 网络成功抓取、云 OSS/CDN 权限和生产大文件并发仍需部署环境验收；测试没有修改真实 Bucket 权限。
- 现有站点 sitemap 未增加动态文章链接，文章页面已有 SSR/canonical；完整博客 SEO 和 taxonomy 属于后续博客增量需求。

## 14. 测试与构建

后端：JDK 21，Maven reactor 到 launch；选择 DocumentPipelineTest、BlogDocumentLifecycleTest、Asset*Test、BootContextSmokeTest、OpenApiContractTest、ServiceArchitectureTest。

其中 DocumentPipelineTest 12 项，真实 PostgreSQL 18.6 生命周期 7 项，资产 25 项，启动/安全边界 7 项，OpenAPI 4 项，职责架构 4 项，共 59 项。验证不可变版本、草稿/发布隔离、并发 CAS、资源缺失阻止发布、真实导入资源绑定、MD/ZIP 内容、下载权限、DB 回滚及新文件补偿、签名失败保留保存结果、路径/SSRF/转义和断流 ZIP。

数据库测试仅接受命名为 blog_document_test 的 localhost 独立库，实际执行 V1–V5，不连接维护者开发库；结束后关闭临时 PostgreSQL。

复现命令（需先准备独立测试库，Maven/JDK 配置见项目现有说明）：

```powershell
$env:BLOG_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:55437/blog_document_test'
mvn -f backend/pom.xml -pl mai-portfolio-launch -am package '-Dtest=DocumentPipelineTest,BlogDocumentLifecycleTest,Asset*Test,BootContextSmokeTest,OpenApiContractTest,ServiceArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

前端：Nuxt typecheck、Vitest 20 文件/231 项；两套独立生产 Nuxt 构建，真实 Chrome 浏览器中的博客流程 2 项、缓存/锚点/Toast 回归 8 项。浏览器测试使用隔离模拟 API；后端生命周期使用真实数据库和模拟外部存储。这是分层验证，不声称完成真实云 OSS 全链路联调。

浏览器测试为 app/test/blogLifecycle.integration.mjs 与 app/test/persistentState.integration.mjs；工具运行时的 Playwright 可通过 PLAYWRIGHT_MODULE_PATH 提供，实际 Chrome 路径通过 BROWSER_EXECUTABLE 指定，不新增项目运行时依赖。

结果：后端测试通过，Maven 打包通过；前端类型检查、单元测试、生产构建与浏览器检查通过。通过测试之外已检查职责、依赖方向、Source of Truth、复用入口及失败行为；上述实际范围与债务保留在报告中，不把未实施能力算作完成。
