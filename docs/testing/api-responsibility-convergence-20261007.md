# API 职责收敛与冗余清理（2026-10-07）

本次以当前代码、Nuxt 实际调用和维护者最新要求为准。交接文档、历史测试与历史验收记录是证据，不作为继续保留旧设计的指令。维护者已选择保留私有 OSS Bucket，返回有期限的签名地址；未修改真实 Bucket 权限、真实数据库、Redis、OSS 对象或原运行服务。

功能分支：`feature/api-responsibility-convergence`。父分支：`feature/backend-maintainability-fixes`，起点 `acf325ec74822434f283323b3a6814d0fed88527`。在独立 worktree `.local-backups/convergence` 实施。最初按审核前不合并执行；维护者于 2026-10-08 明确此限制仅适用于 main，允许合并到其他分支，因此本轮将已验证实现同步回非 main 父分支与原工作区。未推送，未合并 main。

## 修改前扫描与业务判断

扫描 Controller、Service/Impl、DTO/VO、Mapper、Storage/OSS、Asset/Media、Account/SiteProfile/Project/Taxonomy、Swagger 和 Nuxt 调用/缓存/页面/测试。初始生产 Java 分类清单包含 157 个文件，前端 app 清单包含 80 个文件（含测试）。Blog/Agent 目前仅有模块骨架，不能把待实现导出说成现有能力。

稳定核心：

- 账号资料来自 `user_account + user_profile`；网站资料来自单条 `site_config`，不是同一个业务事实。
- 资产记录真实类型、用途、上传者、存储位置、状态；上传生命周期为 PENDING → 存储写入 → READY，失败记录 FAILED。
- 上传与业务绑定独立；绑定失败不删除对象，移除封面引用不删除文件。
- 项目版本、发布条件、标签和外链关联由 ProjectService 承担；公开投影不包含隐藏外链和管理配置。
- 认证、当前账号状态、CSRF 和 OWNER 授权沿用 System/Sa-Token 能力。
- 资源展示范围由引用它的业务对象决定；资产位置不由项目发布状态决定。

可预见变化及承载位置：

- 头像、封面、博客图片、项目截图使用同一图片能力；用途的实际大小/像素限制放在 AssetUsage 和已有头像配置，内容校验放在 ImageContentValidator。
- 普通附件与图片的内容处理确实不同，由同一个 AssetService 的已知类型分支表达，不创建各用途 Service。
- 存储厂商由 ObjectStorage 隔离；签名期限属于 Storage 配置，不写在业务 Controller。
- 地址会到期，由响应 expiresAt 和统一前端刷新/缓存判断承载；未绑定的上传预览可以重新读取资产信息。
- 复用既有 AssetUploadComponent、ObjectStorage、TaxonomyQueryService、ProjectService、账号认证和 OWNER 授权，不引入 Factory、Registry、抽象基类或万能 Service。

发现的重复与分类问题：

1. 头像与项目图片重复接收、校验、上传编排；上传和头像绑定混在一个动作。
2. 资产用途曾通过 storage_key 前缀推断，而不是明确元数据。
3. 头像、项目封面、网站媒体、管理预览存在四条静态文件字节代理链。
4. 网站资料 admin/public 两个读模型和两个读方法表达同一份公开事实，管理仅额外需要并发令牌。
5. 项目虽已共用核心 Service，Controller/路径仍按调用方重复分类；标签也按 admin 命名。
6. 多个 *ApiVo 为 Swagger 复制 R 的字段，容易偏离实际响应。
7. 前端保留专用上传 Client、代理预览 URL、头像地址拼接和无期限缓存。
8. 绑定校验若直接生成签名，业务校验会被存储配置/签名失败影响；已拆出仅元数据查询。
9. 新接口若只改 URL，会绕过原按 admin/account 路径分类的认证拦截；已改为 API 写操作和登录/角色注解决定认证边界。

## API 收敛表（修改前先在会话输出，现补充最终落点）

| 当前接口/能力 | 实际职责 | 是否重复 | 目标处理 | 合并到哪里 | 理由 |
|---|---|---|---|---|---|
| POST /account/avatar、POST /admin/assets/images | 接收、验证、写 OSS、保存资产 | 是 | 统一上传，头像另行绑定 | POST /assets + PUT /account/avatar | 用途不同不构成两套上传生命周期 |
| GET /account/avatar | 转发当前账号头像字节 | 是 | 删除 GET | AccountProfileVo.avatarUrl | 浏览器直连私有 OSS 签名地址 |
| GET /admin/assets/images/{id} | 管理图片字节预览 | 是 | 删除字节接口 | AssetVo.resourceUrl | 地址生成与字节传输不同职责 |
| GET /projects/{id}/cover | 按项目状态代理封面 | 是 | 删除 | 项目 cover.url | 下架不要求撤销文件地址 |
| GET /public/profile/media/{id} | 代理网站头像/简历 | 是 | 删除 | SiteProfileVo 的 avatarUrl/resumeUrl | 静态资源直连 |
| GET /public/profile、GET /admin/profile | 读取同一份网站资料 | 是 | 一次读取、同一投影 | GET /profile | updatedAt 可以公开，不是私密管理事实 |
| PATCH /admin/profile | 修改网站公开文本 | 否，路径分类不合理 | 改为统一资料入口的受保护操作 | PATCH /profile | 写能力与角色是操作属性 |
| ProjectController、AdminProjectController | 同一项目生命周期 | Controller 分类重复 | 合并 Controller | /projects | 既有 ProjectService 保持核心唯一 |
| AdminTagController | 技术标签选择 | 无独立 admin 业务 | 改为领域名称 | GET /tags | OWNER 权限保留 |
| ImageAssetUploadService | 头像专用薄适配 | 是 | 删除 | AssetService | 不再单独承载上传 |
| ProfileMediaService、ProjectCoverService、AssetContentService | 静态媒体字节代理 | 是 | 删除接口与 Impl | 业务投影 + AssetQueryService | 不再转发 OSS 字节 |
| AccountProfile / SiteProfile | 账号资料 / 网站介绍 | 否 | 保留独立模型与 Service | System / Portfolio | Source of Truth、修改生命周期不同 |
| PublicProjectVo / ProjectEditorVo | 公开卡片 / 完整编辑快照 | 否 | 保留投影差异 | 一个 ProjectService | 防止隐藏外链、状态、版本泄露 |
| *ApiVo | 为文档复制 R<T> | 是 | 删除 | springdoc 从实际 R<T> 推断 | 文档不另建响应事实 |
| 博客导出 | 受保护动态文件组装 | 目前未实现 | 不新增虚假实现 | 后续 Blog 的 ExportService | 本次是职责收敛，不是补完博客业务 |

表中路径均有 `/api/v1` 前缀。

## 重构前与重构后分类

重构前：

- Auth：会话/注册；Account：资料/密码及头像上传、字节读取。
- public/profile 与 admin/profile；独立 public profile media。
- projects 列表、admin/projects 编辑/发布、projects cover 字节接口。
- admin/tags、admin/assets/images 上传/字节预览。

重构后：

| 领域 | 接口 | 权限与职责 |
|---|---|---|
| auth | GET/POST/DELETE /auth/session；POST /auth/register | 保持现有认证语义；登录/注册字段异常为专用认证异常 |
| account | GET/PUT /account/profile；PUT /account/avatar；PUT /account/password | 有效登录；头像绑定仅本人上传且 READY 的 AVATAR |
| profile | GET/PATCH /profile | 公开读取同一网站模型；PATCH OWNER + CSRF + updatedAt |
| assets | POST /assets；GET /assets/{id} | AVATAR 有效登录；IMAGE/ATTACHMENT 上传 OWNER；信息读取限上传者或 OWNER |
| projects | GET/POST /projects；GET/PATCH /projects/{id}；POST /projects/{id}/publish、unpublish | PUBLIC 列表匿名；MANAGE、编辑及写入 OWNER；写入 CSRF |
| tags | GET /tags?kind=TECH | 保留既有 OWNER 技术标签选项能力 |
| blogs | 无实际业务接口 | 模块骨架，导出尚未实现 |

资产 GET 是统一 JSON 资源信息能力，用于刷新到期地址，不是保留旧 preview 字节接口或旧路径适配层。

## 合并与删除

- 将头像与项目图片上传收敛到 AssetController → AssetService/Impl → ImageContentValidator/AssetUploadComponent → MediaAssetMapper/ObjectStorage。
- AccountProfileService 不再接收 MultipartFile、读取头像字节或拥有上传事务；只负责账号资料与资产引用绑定。
- ProfileService 更名 SiteProfileService；删除 adminProfile/publicProfile 两种读方法，统一 profile/toVo。
- 原 ProjectService 已经唯一，本次合并两个项目 Controller，不把一次 Controller 合并虚报为两套核心 Service 合并。
- 删除 ImageAssetUploadService/Impl、ImageUploadComponent 接口和专用上传封装；图片检查作为明确职责的 ImageContentValidator 保留。
- 删除 ProfileMediaService/Impl、ProjectCoverService/Impl、AssetContentService/Impl、AssetContentData。
- 删除 AdminProfileController、AdminProjectController、PublicProfileMediaController、PublicProjectCoverController；AdminTagController 更名 TagController，FileUploadController/Service 更名 AssetController/Service。
- 删除 AdminProfileVo/PublicProfileVo 两套网站读模型，使用 SiteProfileVo；AdminProjectVo 更名 ProjectEditorVo。
- 删除 AccountProfileApiVo、SessionApiVo、RegistrationApiVo、PublicProfileApiVo、AdminProfileApiVo 五个 Swagger 包装模型。
- 删除 ImageUploadPurpose、FileUploadConstants、旧媒体错误常量、旧 admin 路径拦截器、无引用路径常量及专用代理测试。
- Mapper 未存在需复制/合并的旧专用查询方法；仍用于资料、项目版本与引用的 Mapper 保留。资产批量查询替代封面逐项查询。
- 旧测试同步替换为元数据归属、签名直连、统一上传、批量查询和被删除路由的验证。
- README、当前接口清单、验收夹具和前端 SEO 集成调用同步更新；历史 handoff/testing 文档保持历史证据身份。

移除的角色分类：API 的 admin/public 前缀、Admin* Controller、网站 admin/public 双模型、AdminAuthorization 路径拦截。保留的真实差异：账号与网站资料、公开项目卡片与编辑快照、登录/OWNER 权限、前端 /admin 页面路由（导航与访问控制，不是复制业务能力）。OwnerRoleService/OwnerAuthorizationService 是角色规则与鉴权能力，不是另一个 OwnerAccount 业务实体。

## 静态资源完整链路

上传：

`Nuxt useAssetApi.upload(file, usage)`
→ `POST /api/v1/assets`
→ 登录/状态/CSRF、用途授权
→ bounded 文件接收、图片解码前检查/重编码或附件下载 MIME
→ AssetUploadComponent：短事务 PENDING → OSS 写入（事务外）→ 短事务 READY
→ 保存 usage/uploader/source/type/key/哈希等元数据
→ OSS SDK 生成 HTTPS 签名 GET 地址
→ 返回 AssetVo（id/resourceUrl/expiresAt/contentType/byteSize/usage）。

绑定与展示：

- 头像：PUT /account/avatar → 锁定当前账号 → Asset 元数据核对 type/usage/uploader/READY → 保存 user_profile 引用 → 返回头像签名 URL/到期时间。
- 封面：项目完整快照携带 coverMediaId → ProjectService 校验 IMAGE 用途元数据 → 同版本事务保存关联 → 批量资产投影生成 cover.url/expiresAt。
- 网站头像/简历：SiteProfileService 根据 site_config 的引用读取 READY 资产并生成地址。
- 浏览器通过 URL 直接 GET OSS，不经过 Spring Boot 字节转发。项目下架只影响公开业务查询，已经签发的资源地址按原有效期存续。
- Nuxt 不修改签名 URL，不追加 rev/v；响应缓存按最早 expiresAt 失效，展示页提前 30 秒刷新，重新激活页面可重试。失败不循环请求；编辑封面地址刷新不覆盖未保存表单。
- 签名默认 1h，配置 `portfolio.media.signed-url-ttl` / `MEDIA_SIGNED_URL_TTL`，不允许零/负值。签名 URL 是临时访问凭据，日志整值脱敏。
- 普通附件限制 64 MiB、保存为 application/octet-stream；不是大文件博客导出。local 存储仍可做离线存储测试，但明确不提供 OSS 签名直连，不暗中恢复后端代理。

## 博客导出访问链路

当前事实：Blog 模块只有骨架，没有可运行 ExportService 或导出 API，本次没有创建博客导出接口。因此“当前博客导出链路”尚不存在。

后续真实需求的职责已明确：
登录/当前账号状态 → 博客可见性与逐篇导出许可 → ExportService 组装正文与必要资产 → OutputStream/StreamingResponseBody 按需流式写出。ObjectStorage.open 的底层读取能力保留，可供动态组装复用；普通静态图片不因此恢复代理。未实现归档资源抓取、格式转换或整文件 JVM 缓冲导出。

## 前端同步

1. 新增唯一 useAssetApi，头像和封面共用 POST /assets。
2. 头像上传后独立 PUT 绑定，流程跨请求检查登录世代变化；API 不提交 accountId、外部 URL 或角色。
3. 删除 account.uploadAvatar 与 project.uploadProjectCover 两个专用 Client 方法；删除 api/adminProfile.ts，网站读取/写入共用 api/profile.ts。
4. 项目、标签、网站 API 改到统一路径，无旧 API fallback、适配层、双写或双请求。
5. SSR 公共资料与管理缓存仍各自保证数据/登录隔离，使用同一响应类型及地址到期判断。
6. 保存网站资料同步清理 Nuxt 公共资料缓存；保存项目保持公开列表缓存失效。
7. AccountMenu/AccountSettings 原样使用头像 URL；项目 cover、上传结果和资料响应均携带到期时间。
8. 共用 useAssetUrlRefresh，卸载时清除计时器/事件；到期刷新失败通过现有错误状态展示，避免无限重试。
9. robots 不再保留不存在的 admin API 前缀规则，统一排除 /api/v1 的 JSON 接口；管理 HTML 页面导航不改名。

## Maintainability Review

| 检查 | 结论与依据 |
|---|---|
| 增加博客图片、项目截图等同类用途需改几处 | 共用 IMAGE，仅在真实业务绑定处使用资产 ID；上传 Controller/Service 无需复制、无需修改 |
| 新用途有不同真实限制时 | 增加 AssetUsage 的声明及必要内容/权限规则；不新建专用三层。已有 AVATAR 的真实独立限制和归属规则保留 |
| 替换 OSS 依赖 | 修改 Storage 适配/配置及适配测试，业务 Service 继续依赖 ObjectStorage/Asset provider；业务层没有 Aliyun SDK 引用 |
| 重复业务规则 | 上传生命周期一处，图片内容检查一处，绑定归属在账号业务，项目发布条件在项目业务；没有从 key 推断用途的运行时分支 |
| 为测试能过的生产代码 | 无测试用户名、测试路径、伪造签名或接口兼容分支；旧代理测试删除后改测新链路 |
| Source of Truth | Asset 元数据事实与位置、user_profile 账号绑定、site_config 网站事实、project+version+relations 项目生命周期清楚分离 |
| 失败路径 | 未登录/停用/无角色/CSRF、缺参数、非法用途、空文件/超限/读失败/图片损坏/尺寸/伪 MIME、PENDING/READY 持久化失败、OSS 失败、资产未就绪/不归属、签名失败、版本冲突均有明确处理 |
| 配置/异常/日志 | 复用配置绑定、ApiException、统一错误常量与审计；签名/厂商文本不回传、不写业务日志；Servlet 上传上限另有 413 |
| 下次是否被迫复制 | 同类图片/附件不复制上传三层；绑定不同业务事实仍在各自 Service，防止 Asset 成为 God Service |
| 最终场景词搜索 | 生产业务层无 Admin/Owner/User 专属重复上传 Service，无 StreamingResponseBody/InputStreamResource 静态代理；剩余角色鉴权、公开投影、账号实体、真实 avatar/cover 引用均有独立语义 |

主动未提前设计：多云 Registry、存储策略工厂、插件式上传注册器、BaseController/CommonService、资源垃圾回收/引用计数、病毒扫描、CDN 刷新、分片上传、文档格式解析、博客资源归档。它们没有本轮已确认的业务模型，不能为假设性未来堆结构。

技术债与限制：

- V4 只为唯一账号头像引用回填上传者；历史无法确定 provenance 的资产 uploader 仍为空，不伪造归属。这些旧引用可展示，但普通账号不能据此绑定不明归属的资产；OWNER 可管理读取。
- 已存在但未绑定的 READY 对象，以及上传成功而后续绑定失败的对象暂不自动清理。自动删除需要单独确认生命周期与引用关系。
- 附件采用有明确上限的内存接收，不支持任意大文件或分片上传；大文件动态导出必须单独流式实现。
- Bucket 私有权限保持原样；真实 OSS 签名访问、生产网络/CORS、数据库 V4 迁移未在真实环境执行，需部署/联调验证。
- Blog 导出、博客资产绑定/导入归档仍待对应业务任务，基础附件上传成功不代表这些能力完成。
- 新旧 URL 为同步破坏性修改；无第三方客户端兼容要求，后端与 Nuxt 必须作为同一版本部署。

## 测试与构建

- Java 21 + 离线 Maven，全 31 模块 `package` 成功。
- Surefire 共 47 个 suite，214 项：174 执行通过，40 项外部集成测试按条件跳过，失败/错误均为 0。
- 覆盖统一上传与 PENDING/READY/FAILED 生命周期、头像归属/用途、资源查询先授权后签名、图片真实解码边界、OSS SDK 签名/安全异常、批量投影、资料直连、注解认证、真实 Boot 请求与 OpenAPI 泛型响应。
- 完整 Boot 上下文使用不可连接的测试数据源地址、关闭 bootstrap/Flyway、模拟 Redisson，验证新接口匿名读/受保护操作、旧代理路由删除，以及真实 AuthService 的专用登录/注册字段异常。
- Nuxt：19 个测试文件、224 项测试全部通过；typecheck 与生产 build 成功，使用项目要求的 Node 24.19.0。
- 修改后的真实环境权限测试及迁移夹具再次编译/打包成功；未连接真实数据库执行这些测试。
- 构建目录已核对，没有删除的 Controller/Service 旧 class，也没有对应废弃测试报告。Git diff whitespace 检查通过。
- 已有运行服务未重启；未执行实际 V4 迁移、真实 OSS/Redis/PostgreSQL 联调或真实浏览器网络验收。40 项跳过不代表外部功能通过。
- 验证日志位于原工作区忽略目录 `.local-backups/convergence-final-package.log`、`convergence-frontend-test.log`、`convergence-typecheck.log`、`convergence-final-frontend-build.log`。

## 审查方法与结论

沿用 open-code-review 的 OCR delegate 模式：CLI 负责文件筛选和规则解析，主 agent 逐领域检查职责/调用链与变更；没有声称外部模型独立完成审查。OCR 默认排除的删除文件、测试、SQL、文档另行检查。OCR 最终预览 149 个变更项，其中 74 个可审查项已逐项复查，0 跳过；75 个默认排除项（含删除、测试、说明和示例配置）补充审查。额外新增的审查覆盖 JSON 是审查记录，人工核对。详见同目录 api-responsibility-convergence-review-coverage-20261007.json。主要结构问题已修正，没有将未处理的明显结构性问题掩盖为测试通过。

就本次已实现的业务能力，没有保留必须靠复制新 Controller/Service 才能扩展的区域。权限与投影差异、账号与网站资料差异是保留的明确边界。未实现的博客业务不是由一套万能 Service 掩盖。

**下一次新增博客图片、文章附件、项目截图、头像，是否还需要复制新的上传 Controller/Service？不需要。** 图片使用 IMAGE、头像使用 AVATAR、普通附件使用 ATTACHMENT，全部通过同一个资产入口和核心上传生命周期；各业务只补真实绑定/许可规则。

## 2026-10-08 补充清理

维护者指出旧文件清理不够直观。本轮先核对两个实际检出目录：原目录仍是 feature/api-feedback-alerts，含维护者已有暂存/未暂存修改；重构实现与删除记录在独立 worktree 的 feature/api-responsibility-convergence，首个提交 164448d。审核前未合并，因此不能把原目录仍有旧类解释成重构分支仍保留旧实现，也不能将原目录的既有修改覆盖掉。

重构分支复查发现并修正的残留：

- 将 AdminProfileApiTest 与 ProfileApiTest 合并为 SiteProfileApiTest，保留公开字段、未知路由、写入权限、CSRF、校验和版本冲突验证，删除两个旧测试文件。
- 将 frontend/app/test/adminProfile.test.ts 更名为 siteProfile.test.ts，删除旧 API 分类名称。
- 删除 OpenAPI 测试中重复的资料响应/Schema 断言、adminProfile 变量和失效导入；保留真实权限与被删除路由的断言。
- 删除 ProfileEditor 中重复的公开缓存失效逻辑及对应导入。保存后的缓存失效只由统一资料 API 承担，组件负责表单与错误提示；现有缓存测试补充验证一次失效调用。

无引用检查覆盖 Java 生产类、领域常量及前端导出符号。未发现需要继续删除的无引用旧业务类/常量；无显式调用的 AssetController、TagController 与 OpenApiConfig 是由 Spring 注册的入口/配置，不能按字符串引用数删除。前端若干类型在本文件内部使用，并非死代码。DOCUMENT 为既有数据库编码和简历引用类型，AccountProfile 与 SiteProfile、公开项目投影与编辑投影仍有真实业务语义；保留它们不是保留两套实现。前端 AdminShell/AdminProjectList 等表示现有管理页面交互，未对应另一个管理业务 Service。

当前生产源码不存在旧图片专用上传、头像/封面/网站媒体字节代理或 admin/public 网站资料双实现。历史交接与审查报告保留作为历史记录；旧路由名称仅用于删除证据或负向路由测试。

补充验证：全 31 模块从 clean 开始 package 成功；46 个 suite、214 项测试，其中 174 项通过、40 项外部集成测试跳过，失败/错误为 0。合并测试文件使 suite 减少 1，业务用例数量不变。前端仍为 19 个文件、224 项通过，类型检查与生产构建成功。已核对旧源码、旧 class 和旧测试报告无残留。初次运行遇到 JUnit 临时目录权限错误，改用工作区内临时目录后重跑全部测试通过，未改变测试或生产代码绕过失败。验证日志在原工作区忽略目录 convergence-cleanup-package.log、convergence-cleanup-frontend-test.log、convergence-cleanup-typecheck.log、convergence-cleanup-build.log。

父分支仍为 feature/backend-maintainability-fixes，在同一重构功能分支继续修复，提交后等待审核，不合并、不推送。真实 OSS/数据库联调与 V4 迁移未执行，之前列明的技术债不因这次清理而消失。

## 2026-10-08 非 main 分支同步

维护者补充：审核通过才合并的限制只适用于 main，其余分支可以合并。合并链路为 feature/api-responsibility-convergence → 已记录父分支 feature/backend-maintainability-fixes → 该修复分支的原父分支 feature/api-feedback-alerts（当前项目目录）。两段均已核对祖先关系，可快进合并，不改写历史，不接触 main 或远端。

原工作区未提交源码已保存为本地 stash `aa57866fd518a887802d5e114b45fc51b014eb0b`，说明为 `pre-convergence integration: original workspace source WIP 20261008`，其中包含原暂存与未暂存状态。该恢复点不删除、不自动重放；旧常量迁移、旧 Controller 和通用校验冒充认证异常的尝试与当前已验证实现重叠，盲目重放会恢复冗余类或破坏职责边界。需要查阅旧尝试时可从此恢复点读取，不将它们混进本轮提交。

本地开发配置 application-dev.yaml 不进入 stash 或提交，原样留在工作目录；docs/handoffs 未跟踪资料也原位保留。合并前保存了配置与交接文件的哈希校验清单到忽略目录 `.local-backups/integration-preservation-20261008.json`，合并后据此核对。当前 AGENTS.md 的本地 Git 规则同步为只有 main 需要审核前置。

两段快进合并已完成，原项目目录 feature/api-feedback-alerts 已具有本次修复与重构；旧源码及旧 class 无残留，配置和交接文档哈希核对一致，源代码恢复点仍保留。本轮仅同步已验证提交与文档，没有新增生产代码。

后端 clean package（174 项通过、40 项外部集成跳过）、前端 224 项测试和类型检查的结果沿用同一份生产代码。原项目目录另外执行了 `clean package -DskipTests=true`，31 模块编译（含测试源码）/打包成功，并清除了旧 class/旧测试产物；这次没有重复执行后端测试。原目录 Nuxt 生产 build 也成功。日志在 `.local-backups/integration-original-package-20261008.log` 和 `.local-backups/integration-original-frontend-build-20261008.log`。真实数据库、OSS、Redis 和既有运行服务未作写入或重启，main 未合并、远端未推送。
