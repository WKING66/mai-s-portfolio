# Tasks: 个人作品集网站（首版，待确认）

> **任务基线已变化（2026-09-27）**：本地开发后端已切换至 PostgreSQL，统一 DocumentVersion 将替换 `article.body_markdown`。T004/T006/T008/T010 等已勾选项仅记录 MySQL 阶段的历史成果；T007、T033–T053、T060、T062 中的 MySQL/Article/三格式实现描述已失效，不得按旧文本执行。数据层与博客模块以文末 T063–T073 的新任务为准；已完成的展示与鉴权行为须保持不变。

**Input**: [spec.md](spec.md)、[plan.md](plan.md)、[research.md](research.md)、[data-model.md](data-model.md)、[HTTP 合同](contracts/http-api.md)、[编码规范](springboot-nuxt-code-style.md)、[quickstart.md](quickstart.md)。

**当前边界**: Nuxt 前端 + Spring Boot 后端；PostgreSQL 已替换本机 MySQL 成为开发环境唯一关系型主库，旧 MySQL 库保留为可恢复备份来源；私有 OSS 保存二进制资源。不使用或修改本机 Docker，也不创建虚拟盘符映射。不安排正式服务器/域名、Agent 运行模块、Redis/Caffeine 运行依赖、项目源码上传或 Word/PDF 格式实现。博客模块需要普通访客登录后才可导出，但注册/登录与博客一起安排在展示功能之后。Markdown Importer/Exporter 首版可验收，其他格式只保留接口。

**工作方式**: 每项均以 `- [ ] Txxx [P?] [US?]` 标记；`[P]` 仅表示文件互不冲突且前置条件已满足后可并行。路径均相对仓库根目录。后端按 `controller → service（接口 + impl 实现）→ mapper` 三层及配套 `entity/dto/enums/config/common` 组织；下方早期任务路径按职责迁入相应层，不再按业务域平铺。用户可见提示写入命名常量，所有 JSON 成功/失败均遵循 `{code,message,data,details}`（文件字节除外）。站点主人禁止使用 ponytail，本项目任何后续阶段不得启用。测试任务先写出失败用例，再实现并使之通过。所有功能以服务层校验和实际接口结果为准，不把数据库异常当正常校验方式。

**执行顺序调整（2026-09-27）**: 先完成公开个人介绍、技术栈、项目展示与对应管理功能，再完成 A 版视觉、响应式和展示页 SEO/GEO 的独立验收。博客公开阅读、编辑、导入、导出及普通用户鉴权统一移至首版最后；旧博客任务仅供需求追溯，实际执行新任务段。本站公开作品集浏览不要求访客注册或登录；博客导出要求普通访客登录。站长现有登录流程暂不改为固定 SHA-256 摘要认证。

## Phase 1: Setup（工程与技术决策门槛）

**目标**: 锁定脚手架和有分歧的技术决定，使后续任务不会一边开发一边更换底座。

- [X] T001 核对 `specs/001-personal-portfolio/plan.md` 与 `research.md`、`contracts/http-api.md` 的认证/数据访问差异；以站点主人确认的 Sa-Token、MyBatis-Plus、Druid 为首选，同步 `research.md`、`plan.md`、`spec.md` 的决策及 A 版顺序；记录 Redis 服务端 3.2.1 是未来适配测试条件而非首版运行依赖。实际会话/CSRF、分页和数据源兼容性由 T002 及基础阶段测试验证。
- [X] T002 在 `backend/pom.xml` 建 Java 21 / Spring Boot 4.1 工程并用最小测试验证 Sa-Token、MyBatis-Plus、Druid、springdoc-openapi 3.x、SQL 迁移工具与 Boot 4 的启动、会话/CSRF、分页、数据源和 `/v3/api-docs`；若未来引入 Sa-Token Redis 适配，再另用真实 Redis 3.2.1 验证命令/序列化/TTL，不拿现代 Redis 测试代替。若首版组件不兼容，先更新 `plan.md` 和 `research.md` 的替代决定再继续。
- [X] T003 [P] 在 `frontend/package.json`、`frontend/nuxt.config.ts` 建 Node 24 / Nuxt 4 / TypeScript 工程，锁定必要依赖、测试工具及包版本；确认 SSR 可输出含公开数据的初始 HTML。后续按站点主人决定补入 Tailwind CSS 4.3.3 与 `@tailwindcss/vite` 4.3.3，集中主题令牌并让页面实际使用工具类；HTTP 请求仍使用 Nuxt 内置能力，不额外引入客户端库。新增 Tailwind 后已用 Node 24 验证生产构建、Nuxt 类型检查和现有前端测试，构建产物含实际工具类和主题令牌，首页 SSR 保留姓名、GitHub 与邮箱。
- [X] T004 [P] 对接站点主人本机 MySQL（当前检测为 8.0.34/13306），在 `frontend/.env.example`、`backend/src/main/resources/application-dev.yaml` 提供本地开发配置与忽略规则；只使用独立测试库并预留 `MEDIA_STORAGE=local|oss`，不运行 Docker、不提交密码或 OSS AccessKey。
- [X] T005 在 `backend/run-local.ps1`、`frontend/package.json` 和仓库说明文件 `README.md` 中固定本地启动、构建、测试入口；以 `specs/001-personal-portfolio/quickstart.md` 为验收命令来源，不声称尚未运行的检查已通过。当前本地命令使用已安装的 Maven 和 JDK 21；Nuxt 构建和后端定向测试已实测，前端测试需随页面用例补齐后再运行。

**Checkpoint**: 后端技术选型通过最小可运行验证，两个工程和本地 MySQL 可启动；若 T002 未通过，先停在此门槛。

## Phase 2: Foundational（所有用户故事的共享前置）

**目标**: 数据、权限、受控媒体和同源 API 边界先成立；本阶段阻塞后续用户故事。

- [X] T006 在 `backend/src/main/resources/db/migration/V1__initial_schema.sql` 建 12 表：`user_account`、`site_config`、`project`、`project_link`、`article`、`article_import_job`、`tag`、`article_tag`、`project_tag`、`media_asset`、`article_media`、`project_media`；全部使用 `BIGINT UNSIGNED AUTO_INCREMENT id` 主键，状态/类别/角色/0-1 开关用 `TINYINT UNSIGNED`，时间 `DATETIME(3)` UTC；建 `data-model.md` 指定 UNIQUE、查询索引和指向主键的 FK `ON DELETE RESTRICT`，不用业务 CHECK 或级联删除。已在独立 `portfolio_dev`（MySQL 8.0.34）迁移并通过结构测试；8.4 待部署前复核。
> **已归档，勿执行** T007 [P] 在 `backend/src/test/java/portfolio/schema/SchemaContractTest.java` 用真实 MySQL 检查 12 个自增主键、TINYINT 编码、唯一索引/FK、无业务 CHECK/级联删除，并以非法关联与重复值测试服务层应先返回业务错误的目标；测试不可通过仅凭 DDL 成功判定。
- [X] T008 在 `backend/src/main/java/portfolio/config/BootstrapService.java` 初始化唯一 `site_config`、OWNER 账号和 `content/confirmed-content.md` 已确认的公开资料/技术标签；密码从环境读取并安全哈希，`NORMAL` 仅保留账号类型、不创建公开注册入口；项目/文章不写虚构正式数据。实际包路径为 `dev/amai/portfolio`；本地启动一次性建号、Argon2id 哈希和 21 个标签已通过集成测试。
- [ ] T009 [P] 在 `backend/src/main/java/dev/amai/portfolio/common/GlobalExceptionHandler.java` 与各业务 Service 统一 `401/403/404/409/413/422` 和 `{code,message,data,details}`；输入、枚举码、长度、HTTP(S) 链接、关联目标及重复关系先在服务层检查，SQL 异常只作兜底并隐藏原文。当前已覆盖现有接口的统一外壳、Bean Validation、匿名/CSRF 和未知路由；新业务校验及全部状态码仍待对应功能完成。
- [X] T010 在 `backend/src/main/java/portfolio/auth/AdminSessionController.java`、`AdminAuthorization.java` 实现 `GET/POST/DELETE /api/v1/admin/session`、OWNER 且 ENABLED 才可管理、HttpOnly 同源会话 Cookie 和写操作 CSRF；匿名读不建账号，NORMAL 账号不得访问 `/admin/**`。后续按站点主人确认升级为 `GET /challenge` 一次性 RSA-OAEP 公钥 + 密文 POST 登录；原明文字段不再接受，前端通过 Web Crypto 实现并测试。
- [X] T011 [P] 在 `backend/src/test/java/portfolio/auth/AdminSecurityTest.java` 先写匿名、NORMAL、OWNER、失效会话和无 CSRF 写请求的接口测试，再使 T010 通过；禁止密码进入 URL、响应和日志。
- [X] T012 在三层架构下定义 ObjectStorage 接口及本地/阿里云 OSS 实现；PostgreSQL 只存 Asset 元数据，私有 Bucket 存字节，READY 对象不原地覆盖；本地实现仅供测试或显式离线排障，dev 默认连接真实 OSS，不向浏览器返回 OSS 凭据或对象键。已实现统一安全对象键、流式读写、同键禁止覆盖、强制 HTTPS 的 local/oss 条件装配与缺失凭据快速失败；对象存储实现、自动配置和测试已归入 `mai-portfolio-spring-boot-starter-storage`，Java 21 Reactor 全量回归 47 个测试通过（真实 OSS 测试按设计跳过 1 个），另有 1 个 `amai-portfolio` 私有 Bucket 集成测试通过，覆盖 ACL、上传、读取、禁止覆盖、删除及最终清理。Asset 业务授权与坏文件/网络抓取场景仍归 T014/T066/T072 验收。
- [ ] T013 在媒体 Service/Controller 中实现文件类型/真实内容/字节/像素校验、仅 OWNER 可触发的受限网络图片抓取；`GET /api/v1/media/{id}` 每次按当前公开 profile、已发布项目或 BlogPost 的 published_version_id 与版本 Asset 清单授权。草稿媒体匿名 `404`，响应缓存不得在撤稿后继续公开。
- [ ] T014 [P] 在媒体访问测试中先覆盖枚举自增媒体 ID、草稿/下架/旧发布版本与新草稿 Asset 隔离、个人头像或简历授权、READY 对象不可覆盖、坏文件、本地/OSS 两种实现，以及抓取内网/回环/危险跳转的拒绝行为，再使 T012–T013 通过。
- [ ] T015 为唯一性、关联替换及文档版本保存实现 PostgreSQL 事务、服务层复查与 `lock_version` 冲突语义；外部 OSS 工作先于短事务，READY 对象不可覆盖，且不假装 PostgreSQL 与 OSS 原子提交。
- [ ] T016 [P] 在 `backend/src/main/java/portfolio/config/OpenApiConfig.java` 与控制器注解中登记公开/管理 API 的 DTO、会话、CSRF、主要错误；本地开放 Knife4j `/doc.html` 与 `/v3/api-docs`，正式配置默认关闭或保护两者，不能用文档替代请求测试。当前实际存在的站长会话与公开资料接口已完成并通过 OpenAPI/Knife4j 合同测试；后续接口及正式环境关闭策略随对应阶段继续，故整项暂不勾选。
- [ ] T017 [P] 在 `frontend/nuxt.config.ts`、`frontend/app/api/` 建浏览器同源 `/api/v1` 代理、统一响应类型和 Nuxt SSR 公开请求路径；私有内容授权始终交给后端，不在 Nuxt 再写一份权限规则。首页真实资料的代理与原始 HTML 已核验；管理端同源 Cookie/CSRF 仍待验收。

**Checkpoint**: 12 表、初始真实资料、OWNER 登录与 CSRF、受控媒体、统一错误及 Swagger 合同均可通过基础集成测试。

## Phase 3: User Story 1 - 快速认识站点主人（P1，最小可演示增量）

**Goal**: 访客在首屏认识阿霾、看到实际技术栈和经批准的联系入口，空简历与空内容保持诚实状态。

**Independent Test**: 不创建任何项目或文章，匿名打开 `/`，原始 HTML 与页面均包含昵称、职业定位、介绍、技术分类、GitHub 与 Email；Resume 无文件不可下载，LinkedIn 不出现，项目/博客为明确空状态。

### Tests for User Story 1

- [ ] T018 [P] [US1] 在 `backend/src/test/java/portfolio/profile/ProfileApiTest.java` 写公开/管理 profile 合同测试：只返回批准资料、技术分类、头像/简历可用状态；非法邮箱/URL 和未就绪媒体由服务层拒绝。当前公开资料、管理端读写、非法邮箱/GitHub URL、CSRF 与过期更新时间已通过真实 MySQL 集成测试；媒体就绪与上传场景仍待媒体模块，不提前勾选整项。
- [ ] T019 [P] [US1] 在 `frontend/tests/home.spec.ts` 写 SSR 原始 HTML、首页首屏联系入口、空简历/头像/项目/文章、360/768/1440px 和键盘导航的可执行验收。

### Implementation for User Story 1

- [X] T020 [US1] 在 `backend/src/main/java/portfolio/profile/ProfileService.java`、`ProfileController.java` 实现 `GET /api/v1/public/profile` 和 `GET/PATCH /api/v1/admin/profile`，只公开已批准昵称、定位、介绍、GitHub、Email 与现有技术标签；简历 ID 为空时不产生下载 URL。实际包路径为 `dev/amai/portfolio`；管理端完整文本快照以 `updatedAt` 做原子冲突校验，媒体和 SEO 字段不被顺带改写。
- [ ] T021 [US1] 在 `backend/src/main/java/portfolio/profile/ProfileMediaController.java` 实现 OWNER 头像上传/替换入口 `POST /api/v1/admin/profile/media`，先验证媒体 READY 和真实类型，未上传时保持非真人占位。
- [ ] T022 [US1] 在 `frontend/app/pages/index.vue`、`frontend/app/components/ProfileHero.vue` 按已选 A 版实现固定导航、介绍后紧接 Resume/GitHub/Email 按钮、自我介绍和联系区域；Resume 无文件显示待补充且不可点击，不展示未批准社交账号。
- [ ] T023 [P] [US1] 在 `frontend/app/components/TechStack.vue` 和 `frontend/app/assets/css/tech-stack.css` 按语言、框架、工程部署、数据技术展示已确认 Logo 与名称；Logo 无独立边框/底色，连续横向移动且 `prefers-reduced-motion` 下停止。
- [ ] T024 [US1] 在 `frontend/app/components/EmptyState.vue`、`frontend/app/pages/index.vue` 呈现项目/博客未发布时的真实空状态与导航入口，不把 `samples/` 或静态原型测试卡片当真实成果。

**Checkpoint**: US1 单独可用；首屏与联系区存在，未提供的简历/头像/项目/文章不伪造。

## Phase 4: User Story 2 - 评估项目作品（P1，可用作品集 MVP）

**Goal**: OWNER 管理项目卡片与外部入口，访客只见已发布资料；不上传源码，也不新增站内项目详情页。

**Independent Test**: 新建含图片、贡献、标签、可选链接的项目草稿；匿名不可见；发布后首页和 `/projects` 可见；下架后卡片与旧媒体 URL 立即不可读。

### Tests for User Story 2

- [ ] T025 [P] [US2] 在 `backend/src/test/java/portfolio/project/ProjectApiTest.java` 写项目创建/编辑/发布/下架、必填与 slug 唯一、无效标签/媒体、重复关联、旧 `version`、非法状态码和匿名草稿隔离的合同测试。
- [ ] T026 [P] [US2] 在 `frontend/tests/projects.spec.ts` 写项目卡片/列表、无演示链接、超长标题、图片缺失、外链和公开空状态的浏览器测试；验证无项目源码上传 UI 或详情页入口。

### Implementation for User Story 2

- [ ] T027 [US2] 在 `backend/src/main/java/portfolio/project/ProjectService.java`、`ProjectController.java` 实现公开分页 `GET /api/v1/public/projects` 和管理 `GET/POST/PATCH /api/v1/admin/projects`；草稿默认 `status=0`，服务层校验标题、摘要、本人贡献、slug、标签、链接和 `version`，发布前复查 READY 图片与关联。
- [ ] T028 [US2] 在项目 Service 接口及实现中处理 `project_link` 的 SMALLINT `link_type` 0–3、`is_visible` 0/1，及 `project_tag` 的唯一关系和目标校验；链接只接受 HTTP(S)，服务层先给出重复/无效目标错误。
- [ ] T029 [US2] 在 `backend/src/main/java/portfolio/project/ProjectMediaController.java` 实现 `POST /api/v1/admin/projects/{id}/media`、`.../media/remote` 的上传与受控归档接口；远程入口必须确认使用权并复用 T013 的安全抓取，关联 `project_media` 的 GALLERY=0/COVER=1；事务内最多一张封面，未就绪图片不能发布。
- [ ] T030 [US2] 在 `backend/src/main/java/portfolio/project/ProjectService.java` 实现 `POST .../{id}/publish|unpublish`；发布前服务层检查资料、唯一性、READY 媒体并写 `published_at`，下架不物理删除且立即改变公开/API/媒体可见性。
- [ ] T031 [US2] 在 `frontend/app/pages/projects.vue`、`frontend/app/components/ProjectCard.vue` 按 A 版卡片语言实现 SSR 项目列表和首页重点项目卡片；呈现本人贡献、成果、技术标签、封面与存在时的外链，不生成站内项目详情 URL。
- [ ] T032 [US2] 在 `frontend/app/pages/admin/projects/index.vue`、`frontend/app/pages/admin/projects/[id].vue` 实现 OWNER 项目新增、编辑、图片、标签、外链、发布/下架；无整个项目目录或源码上传控件，`409` 给出冲突提示。

**Checkpoint**: US1+US2 构成可演示作品集 MVP，正式数据库仍不自动放入虚构项目。

## Phase 5: User Story 3 - 历史博客任务（已归档，执行 T063–T073）

**Goal**: 已发布 Markdown 文章可被 SSR 阅读，草稿隔离；本阶段可用测试夹具验证公开读取，不以完成导入编辑 UI 为前提。

**Independent Test**: 通过测试夹具建立一篇发布文章和一篇草稿；匿名可在 `/blog` 找到发布文章并打开正文，不能读草稿；原始 HTML 含正文、标题、描述、规范地址和一致的作者/日期。

### Tests for User Story 3

> **已归档，勿执行** T033 [P] [US3] 在 `backend/src/test/java/portfolio/article/PublicArticleApiTest.java` 写发布过滤、主题筛选、分页、草稿 `404`、安全 Markdown/图片地址与阅读时长合同测试。
> **已归档，勿执行** T034 [P] [US3] 在 `frontend/tests/blog-reading.spec.ts` 写博客列表/详情、代码/引用/图片、原始 HTML、404、文章结构化数据与键盘阅读测试。

### Implementation for User Story 3

> **已归档，勿执行** T035 [US3] 在 `backend/src/main/java/portfolio/article/MarkdownRenderer.java` 实现 Markdown 到安全 HTML 的渲染，禁用或过滤危险原始 HTML/链接，不允许第三方原图回退；可编辑真源仍只在 `article.body_markdown`。
> **已归档，勿执行** T036 [US3] 在 `backend/src/main/java/portfolio/article/PublicArticleService.java`、`PublicArticleController.java` 实现 `GET /api/v1/public/articles`、`GET /api/v1/public/articles/{slug}`，仅查 `status=1`，服务层提供主题、发布时间、作者、阅读时长和站内图片引用。
> **已归档，勿执行** T037 [US3] 在 `frontend/app/pages/blog/index.vue`、`frontend/app/pages/blog/[slug].vue` 实现 SSR 列表/详情、代码块、标签、日期、阅读时长与缺图说明；首页次级入口只引用已发布文章。
> **已归档，勿执行** T038 [US3] 在 `frontend/app/pages/blog/[slug].vue`、`frontend/app/pages/blog/index.vue` 配置逐页 title、description、canonical 与可见内容一致的 Article/BlogPosting 数据；草稿/后台不得进入公开路由或结构化数据。

**Checkpoint**: 公开博客阅读链路独立通过；US4 接入导入/编辑后复用同一公开读取规则。

## Phase 6: User Story 4 - 历史博客任务（已归档，执行 T063–T073）

**Goal**: 一份 Markdown 正文覆盖站内编辑、`.md`/ZIP 导入、本地/网络图片归档、草稿预览、发布/下架和三格式导出；每次访客下载重新授权。

**Independent Test**: 两图样本导入成草稿、可编辑预览并发布；另一篇站内文章可保存和重登；OWNER 可导出草稿/发布文章的 `.md/.docx/.pdf`，访客只在逐篇允许且已发布时可下载；撤销或下架后旧地址失败。

### Tests for User Story 4

> **已归档，勿执行** T039 [P] [US4] 在 `backend/src/test/java/portfolio/article/ArticleAdminApiTest.java` 写新建/编辑/预览/发布/下架、Markdown 唯一正文、标签、下载许可默认 0、版本冲突和服务层非法输入/关系校验测试。
> **已归档，勿执行** T040 [P] [US4] 在 `backend/src/test/java/portfolio/imports/MarkdownImportTest.java` 用 `samples/markdown-import/` 写 `.md`、保留相对路径的单 Markdown ZIP、两张本地图片、缺图、目录穿越、重名/超限、Word/PDF 拒绝和绝不自动发布测试。
> **已归档，勿执行** T041 [P] [US4] 在 `backend/src/test/java/portfolio/media/RemoteImageSecurityTest.java` 写 `rightsConfirmed`、内网/回环/元数据 IP、DNS/跳转、超时、伪装图片、超大文件及失败引用不回退第三方 URL 的测试。
> **已归档，勿执行** T042 [P] [US4] 在 `backend/src/test/java/portfolio/export/ArticleExportTest.java` 写 OWNER 草稿/发布和访客逐篇许可矩阵、撤销/下架后直接请求、三格式可打开且不改变文章状态的测试；覆盖中文、标题、代码、链接、表格和归档图片。
> **已归档，勿执行** T043 [P] [US4] 在 `frontend/tests/blog-admin.spec.ts` 写 OWNER 登录后站内 Markdown 编辑、ZIP 导入问题展示、图片上传/网络归档确认、草稿预览、发布/下架、三格式导出和权限提示的端到端测试。

### Implementation for User Story 4

> **已归档，勿执行** T044 [US4] 在 `backend/src/main/java/portfolio/article/ArticleService.java`、`AdminArticleController.java` 实现管理列表、新建、编辑、预览、发布/下架和逐篇下载许可；`source_type` 0=EDITOR/1=IMPORT_MARKDOWN，状态 0/1、许可 0/1，保存时重算阅读时长，`version` 防覆盖。
> **已归档，勿执行** T045 [US4] 在 `backend/src/main/java/portfolio/article/ArticleTagService.java` 实现文章只关联 `TOPIC` 标签，先验证目标和重复；发布前检查标题、摘要、slug、正文、所有引用媒体 READY，未发布内容与预览只给 OWNER。
> **已归档，勿执行** T046 [US4] 在 `backend/src/main/java/portfolio/imports/ImportAdapter.java`、`MarkdownImportAdapter.java` 定义未来格式接入边界，但首版仅注册 Markdown；`.docx/.pdf` 输入明确 `422`，不实现空壳转换器。
> **已归档，勿执行** T047 [US4] 在 `backend/src/main/java/portfolio/imports/MarkdownImportService.java`、`MarkdownImportController.java` 实现 `POST /api/v1/admin/imports/markdown`；纯 `.md` 或恰一篇 `.md` 的 ZIP，限制条目/展开字节、绝对路径与 `..`，解析相对图片、记录 `article_import_job.issues_json` 并始终生成/保持草稿。
> **已归档，勿执行** T048 [US4] 在 `backend/src/main/java/portfolio/imports/MarkdownImportService.java` 集成 T013 的网络图片抓取与 `rightsConfirmed`：仅 HTTP(S) 公网目标，约束端口、解析/重定向、时长、字节、像素及真实内容；未确认或失败时逐项记录安全摘要，不保存凭据、不让浏览器回退加载原始 URL。
> **已归档，勿执行** T049 [US4] 在 `backend/src/main/java/portfolio/article/ArticleMediaController.java`、`ArticleMediaService.java` 实现文章图片上传/远程归档与 `article_media` INLINE=0/COVER=1/OG_IMAGE=2 关联；将成功引用重写为 `/api/v1/media/{id}`，未归档引用在预览中警告且阻止发布，封面替换事务内最多一张。
> **已归档，勿执行** T050 [US4] 在 `backend/src/main/java/portfolio/export/ArticleExportService.java` 先用含中文/代码/图片/表格的样本验证转换依赖，再实现 `.md/.docx/.pdf` 流式生成；转换器只从已授权归档媒体取得图片字节，生成文件不持久化、不改变文章状态。
> **已归档，勿执行** T051 [US4] 在 `backend/src/main/java/portfolio/export/ArticleExportController.java` 实现公开 `/api/v1/public/articles/{slug}/export` 与管理 `/api/v1/admin/articles/{id}/export`；访客每次核验 `status=1 && allow_visitor_download=1`，OWNER 可导出自己草稿与发布文章，非法 format 明确拒绝。
> **已归档，勿执行** T052 [US4] 在 `frontend/app/pages/admin/articles/index.vue`、`frontend/app/pages/admin/articles/[id].vue` 实现 Markdown 原文编辑与安全预览、标签、草稿/发布/下载许可、图片问题列表和 `409` 冲突提示；不加富文本第二真源。
> **已归档，勿执行** T053 [US4] 在 `frontend/app/components/MarkdownImporter.vue`、`frontend/app/components/ArticleExporter.vue` 提供 `.md`/ZIP 导入、网络图片授权确认和 `.md/.docx/.pdf` 下载入口；Word/PDF 仅导出，不误导为可导入。

**Checkpoint**: 内容管理完整纵向流程通过，图片本地与 OSS 模式、导出权限和三份可打开文件都有实测证据。

## Phase 7: User Story 5 - 已选 A 版视觉方向落地（P2）

**Goal**: 把已定的 A 版设计应用于真实数据驱动页面；不重新制作或比较三版静态网页。

**Independent Test**: 对照 `prototypes/concept-a.html` 与 `HANDOFF.md`，实际首页保留介绍下的联系按钮、蓝调光晕、半透明分区卡片、无边框移动 Logo；指针有流动/倾斜/柔光，触控与减少动态效果保持静态可读。

### Tests for User Story 5

- [ ] T054 [P] [US5] 在 `frontend/tests/visual-behavior.spec.ts` 写浅/深色、指针/触控、`prefers-reduced-motion`、键盘焦点和 360/768/1440px 的布局/交互断言；卡片动效不承载唯一信息。

### Implementation for User Story 5

- [ ] T055 [US5] 在 `specs/001-personal-portfolio/design-decision.md` 汇总已确认 A 版的保留/避免元素和待验证假设，引用 `prototypes/concept-a.html`、`HANDOFF.md`；标明三版原型已完成、不再排重做任务；正式首页首屏后沿用 A 版先展示技术栈的顺序，再进入项目与博客，不再次要求站点主人选择。
- [ ] T056 [US5] 在 `frontend/app/assets/css/theme.css`、`frontend/app/components/SurfaceCard.vue` 落地浅/深色自动主题、蓝调光晕、半透明大卡片、缓慢流动表面光、指针跟随柔光和小幅倾斜；触控/减少动态效果关闭连续位移与视差。
- [ ] T057 [US5] 在 `frontend/app/pages/index.vue`、`frontend/app/pages/projects.vue`、`frontend/app/pages/blog/index.vue` 统一选定风格与信息权重：项目/博客是核心能力证据，教育/证书仅在主人提供真实内容时展示，不凸显实习空缺或杜撰成果。

**Checkpoint**: A 版成为真实 Nuxt 页面的一致视觉语言，先前原型仍作为对照材料而非正式内容。

## Phase 8: Polish & Cross-Cutting Concerns（首版验收）

- [ ] T058 [P] 在 `frontend/server/routes/sitemap.xml.ts`、`frontend/app/layouts/admin.vue` 和公开页面元数据中完成 SEO/GEO 基础：初始 HTML 的正文/title/description/canonical/OG、只列发布内容的 sitemap、后台/预览不可索引；不承诺搜索排名或 AI 引用。
- [ ] T059 [P] 在 `backend/src/test/java/portfolio/openapi/OpenApiContractTest.java` 验证 `/v3/api-docs` 的公开/管理路径、DTO、认证/CSRF/关键错误与实测一致，正式配置下匿名无法用 Swagger UI 调试管理接口。
> **已归档，勿执行** T060 在 `backend/src/test/java/portfolio/integration/PortfolioFlowTest.java` 和 `frontend/tests/e2e.spec.ts` 跑匿名→OWNER→草稿→媒体→发布→下架→撤销下载全流程；核验 12 表元数据、无孤儿关联、非法输入稳定错误和自动化结果。
- [ ] T061 [P] 在 `frontend/tests/accessibility-performance.spec.ts` 核对 360/768/1440px、键盘、缩放、减少动态效果、普通移动网络首屏身份文字 3 秒目标；记录受众 10 秒识别/两次操作找项目等人工验收结果于 `specs/001-personal-portfolio/acceptance.md`，未测不填通过。
> **已归档，勿执行** T062 在 `specs/001-personal-portfolio/quickstart.md` 逐项记录真实本地启动、`backend/mvn test`、`frontend/npm test`/build、`.md/.docx/.pdf` 打开、真实私有 OSS 上传/读取/撤稿隔离与失败恢复；未取得 OSS 测试环境时明确标记未完成，不能以本地模拟通过替代。

**Checkpoint**: 本地首版验收完成后，另开部署决策；服务器、域名、备案、正式备份/告警和第二版 Agent 不在本任务清单交付范围内。

## Dependencies & Execution Order

1. **T001–T005 Setup** → **T006–T017 Foundation** → 任何用户故事。T001/T002 是技术门槛；T003/T004 可在选型验证期间各自独立推进。
2. **US1 T018–T024** 与 **US2 T025–T032** 在 Foundation 完成后按优先级推进；US2 的首页卡片接入 T022，但项目 API 与管理流可独立测试。US1 单独是“身份首页”最小演示；US1+US2 是对外更有说服力的作品集 MVP。
3. **US5 T054–T057** 紧接 US1+US2 完成首页和项目页的 A 版视觉、交互、响应式；涉及博客页面的部分待博客需求重新确认后再接入。T058 中首页/项目页的 SEO/GEO 基础可先验收，博客相关元数据随后补齐。
4. 历史 **US3 T033–T038** 与 **US4 T039–T053** 已归档；先执行并审核 T074 的后端聚合工程与 Blog provider/service 边界，再按 T065–T070 实现博客。T069 普通账号登录是普通访客导出的前置；Document/Asset、显式发布先完成，公开读取与 Markdown 导出随后验收。
5. 第二阶段 Agent 必须先执行并审核 T075 的 Agent provider/service 边界，再执行 T071；T072–T073 的全站验收在博客实现后进行，不阻塞作品集展示评审点。Blog 与 Agent 同 JVM 部署，但 Agent 只能依赖 Blog provider，禁止访问 Blog Mapper/DO/实现类。
6. 每个故事的测试任务先写失败用例，随后按服务/接口→页面→集成验证顺序完成。当前单人执行顺序为 US1→US2→US5 的展示部分→展示页 SEO/GEO→T074→US3/US4→全站验收→T075→Agent。

### Parallel Examples

- T003（Nuxt 工程）与 T004（本地 MySQL/配置）文件独立；T009（错误处理）与 T011（认证测试）在接口约定明确后可并行。
- US1 的 T018（后端 profile 合同测试）与 T019（首页浏览器测试）、US2 的 T025（项目 API 测试）与 T026（卡片浏览器测试）可在各自前置满足后编写。
- T058（SEO 页面）与 T059（OpenAPI 后端）文件独立；新版最终以 T072/T073 集成结果为准。`[P]` 不表示可以跳过前置依赖。

## 历史排期（已作废，仅供追溯）

下表产生于 MySQL/Article/三格式导出的旧方案，**不得用于当前执行或交付承诺**。当前进度按 T063–T073 的真实 PostgreSQL/OSS 验证门槛和每模块审核推进；首版 Word/PDF 不实施。

| 阶段 | 任务 | 估算有效开发日 | 交付/评审点 |
| --- | --- | ---: | --- |
| 技术门槛与工程 | T001–T005 | 3–5 | 后端组合、Nuxt SSR、MySQL 启动可验证 |
| 共享基础 | T006–T017 | 6–9 | 本机独立测试库的 12 表、OWNER/CSRF、媒体授权、Swagger 成立 |
| 身份首页 + 项目 MVP | T018–T032 | 6–9 | 第一个可演示作品集；项目由管理端真实创建 |
| 作品集视觉与展示页 SEO/GEO | T054–T058 的展示部分 | 原 5–7 日估算待拆分 | A 版视觉、首页和项目页独立验收 |
| 博客需求重审 | T033–T053 前置决策 | 待重新估算 | 确认注册、阅读、下载、导入导出边界 |
| 公开博客 | T033–T038 | 原估 3–4，待复核 | 发布文章的 SSR 阅读与草稿隔离 |
| Markdown 管理、图片、三格式导出 | T039–T053 | 原估 10–15，待复核 | 导入、权限、文件质量验收 |
| 全站验收 | T059–T062 及 T058 博客部分 | 待重新估算 | OpenAPI、回归、真实 OSS 测试 |
| **合计** | **T001–T062** | **33–49** | 单人全职约 7–10 周；实际按门槛与缺陷调整 |

**建议检查点**: T001 的组件首选与 A 版首页顺序已经确认；先评审 US1+US2 的作品集 MVP 与 US5 展示效果。T050 导出质量验证和导出权限决策随博客模块延后，不阻塞公开展示；进入博客模块前重审并重估，不能将旧估算当成交付承诺。正式部署方案仍在本地首版完成后决定。

## 2026-09-27 当前有效的数据层与博客任务（取代旧 T007、T033–T053、T060、T062 的冲突描述）

旧排期表中的 MySQL、Article 单正文和三格式导出估算已经失效。本段按功能模块逐项交付审核；PostgreSQL 建表和当前真实数据切换已通过本地验证，未发生过的旧文章/媒体迁移场景仍须单独验证。

- [X] T063 [FR-046][FR-049] 在真实 PostgreSQL 开发库运行 `db/postgresql/V1__portfolio_and_document_core.sql` 的 Flyway 迁移测试；检查全表 Identity、SMALLINT 状态、唯一索引、外键限制、表/字段注释以及 MyBatis-Plus 读写。15 表、15 个 Identity、20 个 RESTRICT 外键与全表字段说明已验收；未修改本机 Docker。
- [ ] T064 [FR-049] 为 MySQL→PostgreSQL 编写确定性数据迁移与回滚方案，先备份并暂停写入；逐项映射 `article` 至 Document/初始 Version/BlogPost、文章标签与媒体到版本清单、导入任务到 Document Import Job，保留密码哈希并推进全部 Identity 序列。用行数、引用、发布状态和新插入 ID 验证；失败保持 MySQL 预览不变。
  当前已备份旧库并迁移实际存在的 1 个账号、1 份资料、22 个标签，逐字段核对一致；旧文章/媒体/导入任务为零，相关映射及有数据时的回滚演练未完成，因此不勾选整项。
- [ ] T065 [FR-017][FR-019][FR-047] 先编写失败测试，再按 Controller→Service 接口/impl→Mapper 实现 Document 与不可变 DocumentVersion，保留 `origin_type` 但不按来源拆正文模型。保存新版本时原子写入 Markdown、标题、摘要、阅读时长、Tag 显示快照和 Asset 清单，并以 `lock_version` 防并发覆盖；旧版本及 READY Asset 均不得原地改写。
- [ ] T066 [FR-048] 实现并测试 `asset://` 引用解析、受控归档、版本资源清单和 AssetResolver。保存时逐一验证资源使用权与 READY 状态；公开媒体访问每次按当前已发布版本、项目或公开资料判权，枚举自增 ID、撤稿及缓存场景均不得泄露草稿资源。
- [ ] T067 [FR-017][FR-036][FR-047] 实现 BlogPost 显式发布/重新发布/下架：发布前验证目标 Version 属于本 Document，原子切换 `published_version_id`；保存草稿不更新公开内容或公开时间。测试 v1→保存 v2→仍公开 v1→显式重发 v2，覆盖标题、摘要、标签、封面、阅读时长和正文。
- [ ] T068 [FR-019][FR-032][FR-033][FR-034][FR-035] 定义 DocumentImporter/DocumentExporter 扩展接口，首版只注册 Markdown；实现 `.md` 与受限图片包导入、预览问题清单、`document_import_job` 来源/格式记录和 Markdown 导出。Word/PDF 输入输出均明确拒绝，不提供伪实现或下载按钮。
- [ ] T069 [FR-036][FR-045] 在博客导出上线前实现普通访客注册/登录与状态验证，复用 Sa-Token 权限能力但不得赋予管理权限；公开阅读仍允许匿名。导出接口每次校验登录、有效账号、当前已发布版本、逐篇下载许可及 Asset 权限；站长导出自己的草稿不受该许可限制。
- [ ] T070 [FR-006][FR-040] 以 BlogPost 的 `published_version_id` 提供公开博客列表/详情及 Nuxt SSR 页面；SEO/GEO 元数据、Markdown 渲染、标签、封面和阅读时长均取同一公开版本，下架后不再出现在页面、站点地图与媒体访问中。
- [ ] T071 [FR-042][SC-026] 第二阶段 Agent 接入前，明确非博客文档的知识用途与可见性模型并另建迁移；公开 KnowledgeRetriever 必须按当前发布版本或经批准的知识用途逐次过滤。先写“草稿已建索引但访客不可检索”及撤稿/重新发布回归测试，不以索引存在性作为授权依据。
- [ ] T072 [FR-043][FR-044] 同步 Knife4j/OpenAPI、错误码常量、前端 API 类型与配置；分别运行后端测试、前端类型检查/构建、真实 PostgreSQL/OSS 集成测试，未运行的检查不得标为通过。
- [ ] T073 [SC-024][SC-025] 完成独立审核点：SQL 与迁移报告先审；Document/Asset 版本化再审；Blog/普通账号/Markdown 流程最后审。每点附通过的测试和保留的风险，之后再重估部署与 Agent 排期。
- [X] T074 [ARCH] 已参考 `orion-visor-w` 将 `backend` 重构为 24 项 Maven Reactor：建立 dependencies BOM、跨业务域公共能力 common、基础设施 framework、业务 modules 和唯一 launch 五层；System/Blog/Agent 按 provider/service 拆分，framework 按 Web、参数校验、接口日志、Sa-Token 安全、MyBatis、数据源、Flyway、OpenAPI/Knife4j、对象存储共 9 个 Starter 拆分。对象存储接口、模型、异常与实现全部归 Storage Starter；common 当前为空，后续只按实际复用需求加入分布式锁抽象、JSON 转换、通用 Util、分页 Request/Vo 等公共内容。业务 service 不再直接声明这些 Starter 已封装的第三方运行依赖。原 HTTP 合同保持，模块化全量测试通过。Blog 业务实体、Service 与 Mapper 仍按 T065–T070 逐项实现。
- [X] T075 [ARCH] 已建立 Agent provider/service 业务域边界，不使用业务 Starter；Agent service 只能依赖 Blog provider，不得访问 Blog Mapper、DO 或实现类。当前未引入 LangChain/LangGraph/Milvus 运行依赖；实际授权与功能回归仍在 T071 实施。
