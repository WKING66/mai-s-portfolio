# 个人作品集项目：Coding Agent 交接

日期：2026-10-07。工作区：`F:\code\ai\codex\projects\mai-portfolio`。

## 0. 先读这几条

维护者要求本会话停止开发，核对已完成、进行中和未完成工作，交由新 Coding Agent 接手。本文是工程交接，不是 Learning Pass，也不是新架构方案。本次只新增本文，没有修改功能代码、运行迁移、测试写入或合并分支。

**不要直接继续开发博客或继续重构。** 当前后端有维护者进行中的未提交整理；先确认其意图，检查当前工作区能否编译/启动，再逐模块核对代码质量、职责与真实接口行为。维护者已明确认为后端实现与组织质量不合格，历史“测试通过”不得用于反驳这一问题。

本文区分三种事实：

- **已提交代码**：截至 `263201a` 的 Git 快照；不等于全部通过维护者审核。
- **历史验收**：`docs/testing/` 记录在指定提交、端口、数据库及条件下的结果；不等于当前工作区通过。
- **未提交工作区**：维护者仍在修改，尚未整体构建或审查；本文不接管、不回退、不擅自提交。

交接核对未读取 `.env`、密码或私钥正文。本文没有凭据。

## 1. Git 状态与接手方式

- 当前分支：`feature/api-feedback-alerts`，HEAD `263201a`，已推送同名 origin 分支。
- 该分支父分支：`feature/anchor-refresh-scroll`，父分支提交 `bcdfde0`。
- 上一层：`feature/project-cards-upload-component`，`651b50f`。
- 本地 `main` 和本地远端跟踪 `origin/main`：`0e89031`。本次未 fetch，远端状态以本地已有跟踪记录为依据。
- 从 `main` 到当前 HEAD 有 **14 个后续提交**。它们通过连续功能分支继承，不是 14 项已合并 main 的功能。不要从旧 main 开始后误认为后续实现不存在，也不要擅自把整条链合并。

| 分支 / 提交 | 主要内容 | 当前交付事实 |
| --- | --- | --- |
| `feature/project-management-display` / `81ee778` | 项目管理与公开展示 | 已提交、推送，在当前祖先链 |
| `feature/project-navigation-login-ui` / `63950f4` | 首页项目锚点、项目栏目入口、登录页面 | 同上 |
| `feature/frontend-auth-interceptors` / `22601ea` | 前端集中鉴权、Auth 可见性组件 | 同上 |
| `feature/profile-management` / `ff16138` | 站长公开资料管理页面 | 同上 |
| `feature/portfolio-seo` / `4827830` | 首页 / 项目 SEO、robots、sitemap | 同上 |
| `feature/visitor-registration` / `7ddffd4`、`20d50d4` | 普通用户注册、独立限流、密码规则与紧凑页面 | 同上 |
| `feature/account-center-ui` / `6099d4a`、`0a092cc`、`e614aeb`、`8e3e70c` | 个人中心、账户菜单、主题 / 布局修复、Pinia 与默认环境修复 | 同上 |
| `feature/project-cards-upload-component` / `651b50f` | 卡片封面 / 倾斜、可复用资产上传组件 | 已提交推送，未合并父分支 |
| `feature/anchor-refresh-scroll` / `bcdfde0` | 锚点首次刷新跳顶修复 | 已提交推送，未合并父分支 |
| `feature/api-feedback-alerts` / `263201a` | 接口成功 / 失败统一 UAlert | 已提交推送，未合并父分支 |

新 Agent 必须遵循本地 `AGENTS.md`：改功能先查 Git 状态，从**经确认的父分支**建 `feature/...`；精确提交本轮文件，维护者审核通过后才合并。不要 reset、清空暂存区、覆盖 dev 配置或全仓库 add。本文当前只保存本地，未提交 / 推送。

### 1.1 未提交后端整理：必须保留

核对时暂存区有：

- `AccountMessageConstants`、`SystemMessageConstants`、`TaxonomyMessageConstants` 从 System 的 `constant` 移至 Common 的 `constants`，暂存重命名后又有未暂存修改（`RM`）。
- 删除 `system/controller/RegistrationController.java`（已暂存）。

工作区还有这些修改（前缀是各自模块的 `src/main/...`）：

- Web Starter：`web/advice/GlobalExceptionHandler.java`。
- Launch：`resources/application-dev.yaml`。
- System：`auth/AuthenticationInterceptor.java`；`controller/AccountProfileController.java`、`AdminTagController.java`、`AuthController.java`。
- System 请求 / 实体：`AccountPasswordRequest.java`、`AccountProfileRequest.java`、`LoginRequest.java`、`RegisterRequest.java`、`RegistrationVo.java`、`TechGroup.java`。
- System 实现：`AccountProfileServiceImpl.java`、`AuthServiceImpl.java`、`LoginThrottleServiceImpl.java`、`PasswordCryptoServiceImpl.java`、`SystemBootstrapServiceImpl.java`。

这些不是上两轮前端修复带入的提交，不能被当作“Agent 漏提交”一并提交。核对期间工作区仍在变化，以上只是快照，接手时重新查看完整差异和暂存差异。

**当前可确认的接手阻断风险：** 未提交 `AuthController` 仍以 `AUTH_SESSION_PATH` 为类级路由，`login` 与移入的 `register` 方法均是没有子路径的 `@PostMapping`（核对时约第 64 / 88 行）。它们具有相同 HTTP 路径与方法；请求体 Java 类型不能充当路由分流条件，可能导致 Spring 启动时报映射冲突。已提交快照由独立 `RegistrationController` 提供 `/api/v1/auth/register`，前端也仍请求该地址。**本次没有启动 / 编译这套未提交后端，也没有修复。** 请先和维护者确认控制器合并意图，再保持正确外部契约处理。

## 2. 已确认、不得重新猜测的要求

- 真实上线项目，不接受“能跑就算完成”；必要注释、统一提示常量、Request / Vo 命名、异常体系、配置体系、Knife4j 风格须保持。
- 本项目禁止使用 ponytail。不要因其他技能推荐而启用。
- 前后端分离；Nuxt SSR；Spring Boot 模块化单体。业务是同级 Maven 模块，不是 Starter；Starter 用于基础设施组件。
- 同一业务模块内部只有一套 Controller → Service 接口 / Impl → Mapper；不要嵌套 `system/tag/service` 等第二套三层。Taxonomy 已并入 System，Portfolio 暂不合并。
- Common 只放真正跨业务通用内容。维护者正在移动常量，归属是否合适需核查真实引用，不由本文判定或擅自恢复。
- Java 21、MyBatis-Plus、PostgreSQL 是当前底座。MySQL 是历史备份来源，不是继续开发的主库。
- 主键自增；PostgreSQL 用 Identity；状态 / 类别用 SMALLINT 适配原 TINYINT 编码要求。服务端做业务校验，不能靠数据库约束代替；最新已允许唯一索引和物理外键，现有 DDL 有 RESTRICT 外键。不要套用更早“绝对禁用外键”的已撤回要求。
- 公开资料与项目匿名可读，普通账号和站长共用登录 / 注销；有效 OWNER 才能管理。前端权限不是服务端授权依据。
- **当前认证为固定 RSA-OAEP SHA-256 + Argon2id + Sa-Token Cookie / CSRF**，不是双 SHA-256，不是 JWT，不是短时 Challenge。旧 LoginChallenge 已删除，不得依据旧任务恢复。
- 本地开发 dev 配置正常开放；部署安全、HTTPS、域名 / 备案 / 云服务器待后续确认，不得擅自把本地配置改成不可用。
- 联调只用默认 **3000 前端 → 9333 后端**，使用维护者原配置、账号和密钥。端口被占先问，不开 3004、19334 等替代环境掩盖问题。不要重置密码或生成替代密钥。
- 不修改本机 Docker，不映射 Z/P 等虚拟盘，不因为路径 / 空格问题搞盘符映射。Git / 下载代理是本机 `127.0.0.1:7897`，优先进程级配置，不擅自改全局。
- dev 真实 OSS Bucket：`amai-portfolio`。不能用本地文件存储假装完成 OSS 验收；不删除维护者或既有验收对象。
- 项目管理只录入卡片资料、标签、截图、外部入口，绝不上传整个项目源码。
- A 版视觉已选，不再重做三版：介绍下方 Resume / GitHub / Email、无边框官方技术 Logo 横向移动、卡片流动 / 光效 / 倾斜、减少动效适配。
- 首页导航“项目”指向 `/#projects`；**当前首页最多四个**，超过四个显示“更多项目”到 `/projects`。这替代最初三个的计划。栏目每页 12，管理每页 20，上限 50。
- 博客放首版最后；不可变 Markdown 文档版本，保存草稿不改变公开内容，显式重新发布。普通访客导出须有效登录且逐篇允许，站长不受逐篇许可影响。
- 首版正常导入 `.md` 并支持 Markdown 导出；Word/PDF 仅预留真实扩展边界，不伪造实现。最新图片 / 文件引入只预留不可用入口；不能把它重新降为纯正文 JSON 导入。未归档资源的发布边界需在博客开发前重新核对。

站点内容：阿霾，Java 后端转向 AI 智能体，项目复盘 / 工具权限 / token 预算 / 性能验证为核心。GitHub `https://github.com/WKING66`、Email `2899964923@qq.com`；Resume 暂空。没有实习经历不应作为短板展示；不杜撰项目经历。技术向量数据库展示已由 pgvector 改为 Milvus，**展示 Logo 不代表引入该数据库运行依赖**。

## 3. 当前模块与文件落点

```text
backend/
  mai-portfolio-dependencies/   BOM
  mai-portfolio-common/         通用响应、公共常量、分布式锁抽象等
  mai-portfolio-framework/      10 个基础设施 Starter
  mai-portfolio-modules/
    mai-portfolio-module-system/     provider + service：账号、鉴权、标签
    mai-portfolio-module-portfolio/  provider + service：公开资料、项目
    mai-portfolio-module-asset/      provider + service：资产元数据、上传、授权
    mai-portfolio-module-blog/       provider + service：骨架
    mai-portfolio-module-agent/      provider + service：骨架
  mai-portfolio-launch/         唯一启动、应用配置、迁移、集成测试
frontend/
  app/api/                     API 请求、RSA、集中请求鉴权
  app/stores/                  account / portfolio / management Pinia
  app/middleware/              auth.global.ts
  app/components/              Auth、菜单、编辑器、展示组件
  app/composables/              业务 UI 状态与交互
  app/utils/                   主题、导航、滚动、日期
  app/pages/                   公开、认证、个人中心、项目 / 资料后台
  shared/utils/seo.ts
  server/routes/               robots.txt、sitemap.xml
```

Framework Starter：web、validation、api-log、security、redis、mybatis、datasource、flyway、openapi、storage。Storage 负责 ObjectStorage / OSS 客户端，不负责资产数据库关系；Asset 负责 `media_asset` 和业务可见性。不要把所有内容都挤进 System，也不要把业务组件做成新的基础设施 Starter。

已核对版本：Spring Boot 4.1.1；Sa-Token 1.45.0；Redisson 4.7.0；MyBatis-Plus 3.5.17；Druid 1.2.28；springdoc 3.0.3；Knife4j 4.5.0；OSS SDK 3.18.5。前端 Node 24、Nuxt 4.5.2、Nuxt UI 4.11.3、Pinia 4.0.3、Tailwind 4.3.3、TS 5.9.2、Vitest 3.2.4。不要无需求升级底座。用户要求 IDEA 2024 测试兼容；本文未复核 JUnit 依赖树或 IDEA 运行，不能声称当前 IDE 验收通过。

## 4. 已实现接口：以已提交契约为准

下面是 `263201a` 及其祖先的功能，不是当前未提交后端已整体通过。具体请求字段、错误码和授权看 Controller、Request / Vo、OpenAPI 与验收记录。

| 接口 | 业务 / 页面 | 已实现边界 |
| --- | --- | --- |
| GET `/api/v1/auth/session` | 共享会话恢复 | loggedIn、username、roles、csrfToken；匿名空角色；不能把查询接口当授权本身 |
| POST `/api/v1/auth/session` | `/login` | 普通 / OWNER 共用、固定 RSA、Origin、登录独立限流、Argon2、Cookie |
| DELETE `/api/v1/auth/session` | 账户菜单 / 认证页 | 登录 + CSRF，清理前端权限及管理数据 |
| POST `/api/v1/auth/register` | `/register` | 固定 NORMAL / 启用、用户名判重、密码 trim / 可见 ASCII 规则、独立注册限流、不自动登录；未提交路由重排有冲突，先核对 |
| GET / PUT `/api/v1/account/profile` | `/account` 个人信息 | 仅本人，读取 / 保存昵称，Pinia 与菜单同步 |
| POST / GET `/api/v1/account/avatar` | `/account` | 本人头像上传 / 私有读取，PNG/JPG，2 MiB、2048 像素边界 |
| PUT `/api/v1/account/password` | `/account` 密码修改 | 验证旧密码、新密码同注册规则；事务提交后撤销会话，前端清理并去登录页 |
| GET `/api/v1/public/profile` | 首页 SSR | 只公开批准资料和技术栈 |
| GET / PATCH `/api/v1/admin/profile` | `/admin/profile` | OWNER、CSRF、文本完整快照、updatedAt 冲突；此接口不等于首页头像 / 简历上传 |
| GET `/api/v1/public/profile/media/{assetId}` | 既有公开资料资源 | 按当前公开绑定读取，不开放任意 Asset |
| GET `/api/v1/admin/tags?kind=TECH` | 项目编辑标签选择 | OWNER、只读技术标签；不是完整主题标签 CRUD |
| GET `/api/v1/projects?view=PUBLIC\|MANAGE&page=&size=` | 首页、栏目 / 管理列表 | PUBLIC 默认且只含已发布，即使 OWNER 已登录；MANAGE 显式授权，非法 view 拒绝，不降级 |
| POST `/api/v1/admin/projects` | `/admin/projects/new` | 创建草稿、标签 / 外链 / 可选封面关联，服务端校验 |
| GET / PATCH `/api/v1/admin/projects/{id}` | `/admin/projects/{id}` | OWNER；修改带版本，旧版本 409；发布后保存直接更新公开卡片 |
| POST `/api/v1/admin/projects/{id}/publish` | 项目管理 | 标题 / 摘要 / 贡献 / 技术标签必需，可无图片和外链；版本保护 |
| POST `/api/v1/admin/projects/{id}/unpublish` | 项目管理 | 下架非删除；下一次公开读取不可见 |
| POST `/api/v1/admin/assets/images` | 项目封面统一上传 | OWNER + CSRF；项目封面用途，不是所有格式的匿名通用上传 |
| GET `/api/v1/admin/assets/images/{assetId}` | 站长封面预览 | READY / 用途隔离；不能用私人头像作为项目图 |
| GET `/api/v1/projects/{id}/cover` | 公开项目卡片 | 已发布项目 + 当前封面关联，每次判权，no-store |
| GET `/robots.txt`、`/sitemap.xml` | Nuxt server routes | 仅已实现公开页面；无正式 origin 禁止索引、sitemap 503 |
| GET `/doc.html`、`/v3/api-docs` | dev Knife4j / OpenAPI | 使用实际后端配置；正式环境策略未部署验收 |

项目没有另建 `/public/projects`、`/admin/projects` 两套列表。没有项目源码上传、站内公开项目详情页或独立远程图片抓取接口。

### 4.1 前端已落地内容

- `Auth.vue` 控制元素可见性；`auth.global.ts` 控制私有路由；`api/authInterceptor.ts` 统一请求能力 / CSRF / 401 / 403；`useAuthState` 连接共享状态。不能重新把角色判断写进每个按钮。
- Pinia `account` 保存会话 / 角色 / CSRF / 本人资料；`portfolio` 保存公开资料与公开项目；`management` 保存管理数据。修改后失效 / 接受新快照，跨页面不重复取同一数据。权限不写 localStorage，不读 HttpOnly Cookie。
- 登录成功后账户下拉包含项目管理（OWNER）、个人中心、退出。个人中心资料 / 密码用 Tabs，管理页与公开资料页布局稳定化，选择器不锁 body 以避免抖动。
- 主题启动脚本在 SSR head 提前恢复，避免先亮后暗；主题偏好和权限恢复独立。
- `bcdfde0`：SSR head 在新文档加载时启用原生 scrollRestoration=auto，初始化滚动返回 false，不在水合后再次覆盖原阅读位置。两项必须配套；后续站内锚点与历史导航仍走路由。不要再次仅“最终 scrollY 正确”就判首帧通过。
- `263201a`：接口结果采用 Nuxt UI `UAlert`；成功绿色勾图标、失败红色警告图标；菜单关闭 / 会话恢复错误可见。普通说明、加载与空状态不是失败，不全部改警告。
- 项目卡片真实封面、胶囊标签 / 外链、每轴最大约 14° 倾斜、流动与指针光效；无图自然布局、不伪造截图；官方 Logo 来源见 `frontend/public/tech/SOURCES.md`。

## 5. Spec / Task 核对与未完成范围

本地 `specs/001-personal-portfolio`、`.agents`、`.specify`、`prototypes`、`samples`、`content` 等被 Git 忽略；新 Agent 若只克隆 GitHub，不会得到这些材料。必要需求已在本文摘要，但执行博客前仍需向维护者取得原评审文档。不要把它们整包提交以“补齐仓库”。

| 能力 | 对应任务 / 来源 | 现状 |
| --- | --- | --- |
| 工程、数据层、模块化 | T001–T005、T063、T074–T076 | 有实现 / 历史测试；T076 一次性挑战描述已被固定 RSA 改造取代；模块质量仍需维护者认可 |
| 首页身份 / 技术栈 / 项目 | T020、T022–T024、T027–T032、T056–T057 展示部分 | 代码已落地；早期勾选和路径没有逐项同步，不能据未勾选判全部未写 |
| 站长资料管理 | T077 / FR-003 | 已提交、有历史真实联调 |
| 公开展示 SEO / GEO | T078、T058 的首页 / 项目部分 | 已实现；正式域名 / 爬虫收录 / 博客 SEO 未完成 |
| 普通注册 | T079、T069 的账号前置 | 已实现；T069 的博客许可导出链路没有完成 |
| 个人中心、UI、通用上传组件、锚点、Alert | 后续用户明确追加、对应 docs/testing | 已提交；不能把追加上传能力等同 T066 / T068 全部完成 |
| 历史 MySQL 文章迁移 | T064 | 有真实空文章库的基础数据切换记录；非空文章 / 图片 / 任务迁移及回滚演练未完成 |
| Document / Version、BlogPost | T065、T067、T080 | **未实现业务**。Blog provider / service 的 Java 文件目前只有 package-info 骨架 |
| 版本 Asset / 引用解析 | T066 | 资产上传 / 项目封面已有；`asset://`、版本资源清单和文档发布判权未完成 |
| 正常 Markdown 导入 / 导出 | T068、T080 | **未实现**；统一 Importer / Exporter、问题清单、编辑 / 预览 UI 也不能宣称已提供 |
| 文章读取 / 下载许可 | T069–T070 | **未实现**；普通账号存在不代表文章导出权限链成立 |
| 主题标签管理 | 首版接口推进补充、T080 前置 | 技术标签读取已做；TOPIC 查询 / 创建 / 必要编辑与博客 provider 接入未完成 |
| Agent / RAG | T071、T075 的实际业务 | **未实现**；Agent 只有 provider / service 骨架，没有检索、模型调用、知识库上传或公开对话 |
| 全站验收 / 部署 | T072–T073、T054、T059、T061 等剩余 | 没有首版整体验收。生产 TLS、云服务器 / 域名 / 备案、移动 / 慢网完整矩阵与部署方案未完成 |

早期 T033–T053 等文章旧模型任务已归档。不要按 Article 单正文、三格式导出、旧路径或旧端口恢复实现。博客以 `unified-document-architecture.md`、`postgresql-document-transition.md`、有效 T065–T070 / T080 与最新用户边界对齐；还不能宣布这些架构已实现。

进行中的工作有两类：维护者的后端整理（第 1.1 节）和等待审核 / 复核的已提交功能。**目前没有隐藏的“写到一半可接着补”的博客或 Agent 实现。**

## 6. 主要调用链、状态与一致性

### 认证与授权

`login.vue / register.vue` → 现有 API / Web Crypto → 同源 Nuxt 代理 → System Controller → `AuthService` / Impl → Origin / Redis 限流 → `PasswordCryptoService` 固定私钥解密 → `UserAccountMapper` / Argon2 → Sa-Token / Redis 会话 → roles / CSRF → Pinia。

受保护请求：`AuthenticationInterceptor` 的登录 / 账号状态 / HTTPS / CSRF 边界，Sa-Token 注解、`AdminAuthorization` 的 OWNER 边界，再进入 Controller / Service。统一项目路由的 MANAGE 也必须受保护，不能只处理 `/admin/**`。注册与登录复用 `FixedWindowRateLimiter`，但分别用登录 / 注册键，不能再次共享额度。

状态变化：注册写 `user_account`，登录 / 注销和 CSRF 写 / 清 Redis，会话读写可能更新 TTL。修改密码写新 Argon2 哈希，提交后撤销会话；应重点审查 DB 提交与 Redis 失败窗口，不能声称跨系统原子。

### 资料与项目

公开资料 / 项目通过 Portfolio Service / Mapper 读取。标签从 System provider 的 `TaxonomyQueryService` 查询，禁止跨模块直接访问 TagMapper。

项目创建 / 编辑 / 发布 / 下架：项目行 + 外链 / 标签 / 封面关系按事务更新，`version` 防覆盖；编辑是完整快照，封面字段缺省 / null 可能解除绑定，旧客户端需要兼容核对。公开读取无新增服务端业务缓存，下架后下一次读取不应见卡片或封面。

站长公开资料使用 `site_config` 与 updatedAt 冲突检查；个人中心是 `user_profile`，不能把账号昵称修改错误同步成站长公开展示资料。

### 文件与 OSS

账户头像旧适配器 `ImageAssetUploadService` → Asset provider 的 `ImageUploadComponent`；项目统一上传 Controller → `FileUploadService` → 同一图片组件。图片类型 / 内容 / 大小 / 像素验证与重编码后 → `AssetUploadComponent` → Storage Starter 的 `ObjectStorage` → OSS。

资产生命周期：短事务 PENDING → 事务外 OSS 上传 → 短事务 READY；失败尽力记 FAILED。不覆盖 READY 对象，不自动删除上传失败 / 未绑定 / 已解绑对象。项目上传仅先建资产，保存项目提交 coverMediaId 才关联；解绑 / 下架不是删除。

通用组件支持已验证内存内容，不意味着已实现文档格式检测、Markdown 图片重写、远程抓取、防 SSRF 或知识库解析。当前通用上传上限 64 MiB、项目图 8 MiB / 4096 像素、头像 2 MiB / 2048 像素；大文件流式处理与孤儿资产治理没有完成。

### 日志、任务与删除

启动横幅输出 Knife4j / OpenAPI 地址；`@ApiLog` 显式接口 AOP，脱敏并截断，不在所有 RestController 无差别记录。拦截器拒绝不经过 Controller AOP，不能用它代替完整安全审计。日志输出策略看 Launch 的 logback 与 dev 配置。

没有引入消息队列、Agent 模型外部 API 或可用的文档异步处理 worker。`document_processing_task` 有 DDL 不代表任务调度已经工作。公开业务下架与媒体解绑没有物理删除链路；OSS 测试脚本可能清理自身随机测试对象，执行前读脚本，绝不清理维护者既有数据。

## 7. 数据与基础设施清单

DDL：`backend/mai-portfolio-launch/src/main/resources/db/postgresql/V1__portfolio_and_document_core.sql`（15 表）与 `V2__account_profile.sql`（user_profile）。这里只核对源码，不连接数据库断言当前表数 / Flyway 历史。

| 表 | 用途 / 使用状态 |
| --- | --- |
| user_account | 账号 / 角色编码 / 启用状态 / 密码哈希，已使用 |
| user_profile | 本人昵称 / 头像，已使用，独立于首页资料 |
| site_config | 站长批准公开资料与资源绑定，已使用 |
| tag | 技术 / 主题标签，技术已用，完整主题管理未做 |
| project、project_link、project_tag、project_media | 展示卡片、外部入口、技术关联、封面关系，已用 |
| media_asset | 元数据 / 存储对象 / PENDING-READY-FAILED，已用 |
| document、document_version | 统一文档身份 / 不可变 Markdown 版本，仅 DDL |
| document_version_asset、document_version_tag | 版本资源清单 / 标签快照，仅 DDL |
| blog_post | 公开发布版本指针 / 下载许可，仅 DDL |
| document_import_job、document_processing_task | 导入 / 处理任务状态，仅 DDL，无可用 worker |

不要为当前已执行过的 V1 / V2 原地改结构；新变更需要新迁移，并核对真实目标库。MySQL 历史库和迁移工具不意味着要继续双库运行。

Redis：Redisson DAO 承担 Sa-Token；固定窗口 Lua 与应用命名空间承担限流；提供分布式锁抽象。集中键与 Lua 分别在 Redis Starter 的 `define/cache/RedisKeys.java` 和 `define/RedisLuaScripts.java`。Caffeine 没引入。用户实际启动命令曾使用 Redis 3.2.100 的 Windows 分发、16379；Redis 3.2.1 是兼容目标，不能用 3.2.100 或现代 Redis 测试伪称精确 3.2.1 已验收。

OSS：真实 `amai-portfolio` 私有 Bucket；前端通过受控后端流读取，不下发 AccessKey、对象键或任意签名 URL。数据库事务不能覆盖 OSS 副作用，暂无自动补偿清理。

前端缓存：Pinia；会话只在初始化 / 明确失效 / 修改后恢复，不应每点一个锚点取一次。主题可持久化，权限不可写 localStorage。管理与公开查询键 / Store 必须分离，不能把管理快照混进公开 SSR。

### 已知留下的验收数据

来自最近已执行记录，不是本次重新查询：

- 默认库项目 ID=2，slug `ui-cover-20261007`，标题“验收示例：作品集封面上传”，当时最终 PUBLISHED、version=5、封面 assetId=3。
- 资产 ID=2、3 是本站真实截图；ID=2 已解绑但保留，ID=3 当前记录绑定。没有删除 OSS 对象；原项目 ID=1 / 原头像未在该轮更改。
- Alert 联调把当前昵称 `owner` 原值保存一次，200；字段值没有变化，但可能推进资料更新时间与审计日志。
- 旧验收使用过隔离 `portfolio_dev.project_acceptance_20261005` schema、夹具用户和独立 Redis 命名空间。它们不是现在默认端口 / 维护者账号的等价替代。

不擅自删除 / 发布 / 改名这些记录，先由维护者决定是否保留。

## 8. 证据与剩余验证

最近前端：`263201a` 下 **18 文件 / 221 用例通过，类型检查与生产构建通过**。默认 3000 / 9333 有真实资料原值保存 200 与越界项目分页 422 的 Alert 联调；浅 / 深主题均有图标、可见、无横向溢出。模板测试不是每个 API 的真实浏览器故障触发。

最近后端：`651b50f` 的记录有完整 Reactor test、Asset / Portfolio 定向 test 与 package 成功；真实默认环境完成统一上传 → 草稿 → 绑定 → 发布 → 公开图片 → 解绑 → 下架 → 重发，详见对应报告。**后来的手工整理没有重新验证，不得将这些结果复制成当前后端通过。** 真实数据库 / OSS / Redis 专用集成测试存在显式环境开关；普通 mvn test 可能跳过它们。

定向证据（阅读时核对“环境 / 未覆盖”段，而不是只看结论）：

- `docs/testing/project-management-acceptance-20261005.md`
- `docs/testing/frontend-auth-interceptors-acceptance-20261005.md`
- `docs/testing/profile-management-acceptance-20261005.md`
- `docs/testing/portfolio-seo-acceptance-20261005.md`
- `docs/testing/visitor-registration-acceptance-20261006.md`
- `docs/testing/registration-ui-password-review-20261006.md`
- `docs/testing/account-center-ui-acceptance-20261006.md`、`account-ui-review-20261006.md`
- `docs/testing/pinia-scroll-acceptance-20261007.md`
- `docs/testing/default-port-auth-navigation-20261007.md`：已有更正，旧最终位置检查不足以证明不闪顶
- `docs/testing/project-cards-upload-component-20261007.md`
- `docs/testing/anchor-refresh-scroll-20261007.md`
- `docs/testing/api-feedback-alerts-20261007.md`

历史前几轮使用过 3004 / 19334 等隔离环境，这是记录事实，不是下个 Agent 可以沿用的现行方法。维护者已明确否决另开端口。移动 / Safari / Firefox / 系统减少动效 / 极慢网络 / 真云故障 / 独立进程多实例 / 公网部署并未完整验收。

## 9. 需要人工重点检查与有争议的实现

此列表用于定位，不是论证当前设计合理，也不是授权继续改代码：

1. **未提交认证 Controller 重排**：同路径两个 POST、旧注册接口是否仍可达、异常语义 / OpenAPI / 注解是否因合并丢失。先解决可启动性与契约，不扩展认证协议。
2. **System / Security / Web 职责**：账号业务、框架注解、登录态 / 状态 / HTTPS / CSRF 检查可能分散或重复查询；按真实调用核对，不再添一套授权。后端目前结构化并不证明实现易维护。
3. **服务端校验 / 常量 / 注释质量**：Request 校验与 Service 校验是否重复或缺失；业务常量是否过度塞 Common；错误码是否稳定、是否暴露底层异常。维护者正在调整的文件优先对齐其意图。
4. **事务和跨系统副作用**：登录的数据库 / Redis写入顺序；改密码 afterCommit 撤销失败；头像 / 封面 OSS 成功而数据库 READY / 绑定失败；不能宣称全部可回滚。
5. **上传抽象是否过多 / 边界是否清晰**：Storage 接口、Asset 通用组件、图片组件、旧头像适配 Service、业务 Service 有多层委托；扩展性意图存在，但层数是否有必要、命名与校验是否重复需要审查，不用“为了未来”当合理性证据。
6. **固定 RSA 取舍**：不防密文重放，不认证 HTTP 服务端；私钥 / 前端公钥配置需一致。不要用 SHA-256 摘要或自动换钥解决配置错误，更不能假装 RSA 代替 HTTPS。
7. **状态 / 角色撤销与缓存**：服务端当前账号检查、前端 401 / 403 分类、Pinia 陈旧权限清理是否一致；不能只根据用户名显示 OWNER，不因所有 403 无条件注销。
8. **媒体公开权限**：资产 ID 自增可枚举；公开接口必须按当前发布 / 当前绑定实时校验。无缓存不代表所有响应、浏览器与未来代理都没有缓存。
9. **SSR / 路由启动 / 滚动**：`router.options.ts` 必须窄导入 `#app/nuxt`，不能改成 `#app` 聚合入口再次触发 dev 初始化循环；首帧脚本和首次返回 false 配对。将来 CSP 必须允许主题与滚动启动脚本。
10. **文档债务**：README 仍有 Taxonomy 独立模块、个人中心未实现等旧说法；`portfolio-v1-interface-progress.md` 仍写通用上传暂缓，已被最新封面组件部分取代；tasks 早期路线和测试路径过时。交接摘要不能替代后续逐项修正文档与实际契约。

## 10. 启动、测试与保密

本文不启动任何服务。上轮 Agent 恢复了默认 3000 Nuxt dev；9333 是维护者 IDEA/dev 后端。接手先查占用，不自行结束维护者进程。运行中的后端也可能仍是旧类，不能据请求能成功就判磁盘新代码可启动。

- 前端：`cd frontend`，Node 24 下 `npm test`、`npm run typecheck`、`npm run build`；默认 `npm run dev`。生产构建与 dev 共享生成目录时，按进程归属协商避免冲突，再恢复 3000。
- 后端：Java 21，`cd backend` 后 `mvn test` / `mvn package`；`run-local.ps1` 会注入本地环境，使用 dev。默认用户希望 IDEA 启动；修改配置 / 重启需按当轮沟通。
- 真实集成脚本：`backend/test-redis.ps1`、`backend/test-oss.ps1`，**先读脚本与环境目标再执行**。未取得正确专用环境不得改默认库代替。
- 根 `.env`、`frontend/.env`、外部私钥和 `.local-backups/default-login.env` 均本地受忽略。只从受忽略文件获取必要凭据，不在聊天、日志、文档或 Git 输出中打印。
- 前端公钥配置为 `NUXT_PUBLIC_AUTH_RSA_PUBLIC_KEY`；后端是 `AUTH_RSA_PRIVATE_KEY_LOCATION`。OWNER 初始化密码只在首次建号时生效，修改 `.env` 不应被当作重置现有账号。
- Git / 网络需要代理时采用本机 7897 的进程级参数；本地 API 不经代理，不写一套替代账号 / 密钥 / dev profile。

## 11. 新 Coding Agent 的最小阅读顺序

1. `AGENTS.md`，Git status / 暂存与工作区差异，第 1.1 节；确认维护者正在整理什么，不触碰已有修改。
2. `docs/auth-refactor-plan .md`（文件名在 `.md` 前有空格）、`docs/auth-unified-session-correction.md`；结合当前 Auth Controller / Service / 拦截器，不照抄历史“仅 OWNER 登录”。
3. 根 POM、BOM、Framework / Modules / Launch POM；System provider / service 的实际职责和跨模块引用。
4. `frontend/app/api/{authInterceptor,session,registration,account,projects}.ts`、`stores/{account,portfolio,management}.ts`、`useAuthState`、`auth.global.ts`、`Auth.vue`。
5. Portfolio `ProjectServiceImpl`、`AdminProjectController`、`ProjectController`、`ProfileService` / 管理资料实现、`ProjectCoverServiceImpl`；System `TaxonomyQueryServiceImpl`。
6. Asset provider 的 `AssetUploadComponent` / Request、`ImageUploadComponent` / Purpose；Asset `component` 实现、上传 Controller、内容授权 Service；Storage Starter 的 ObjectStorage / OSS 实现。
7. V1 / V2 SQL及实际 Migration / Mapper；最后看相应 tests 与真实验收记录，决定哪些检查需要重跑。
8. 只有维护者允许恢复功能开发后，再读本地 `unified-document-architecture.md`、`postgresql-document-transition.md`、spec / plan / tasks 的有效末尾，重新确认博客前置。不照旧清单盲写 Word/PDF、网络抓取或 Agent。

## 12. 接手时应交给维护者的第一份结果

建议先给出短清单：当前工作区哪些是维护者修改；认证注册重排 / 常量移动的实际结果；后端哪些接口已可用、哪些不能启动或有契约回归；System / Portfolio / Asset 的具体职责问题与拟处理范围。等待维护者确认下一模块再开功能分支。

不要以“继续推进首版”为由立刻加博客、重新认证、批量改目录或升级依赖。最终验收仍是默认环境真实前后端操作链，逐接口记录页面、按钮、参数、请求、状态与结果并脱敏；单测和构建是补充，不替代用户体验。

**交接结束。本会话停止继续开发。**
