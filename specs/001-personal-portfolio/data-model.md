# 首版数据库表设计（确认稿）

> **历史 MySQL 设计（2026-09-27 起待替换）**：站点主人已确认 PostgreSQL 成为唯一主库，并采用统一 Document/Version/Blog 模型。本文件仅供迁移字段对照，不再是新建表依据；参见 [迁移审核点](postgresql-document-transition.md)。

目标为 MySQL 8.4 / InnoDB。本文件是后续建表迁移的依据，不代表 SQL 已执行。综合了 [严格评审](data-model-review.md)、[升级建议](data-model-upgrade.md) 与 [功能规格](spec.md)；后续用户决策已覆盖评审稿中“禁用物理外键”的建议：**允许唯一索引和物理外键，但全部业务校验先在服务端完成**。首版共 **12 张表**，不预建 Agent、向量、缓存或评论表。账号表预留普通访客类型，但首版不开放访客注册与登录。

## 总体取舍

| 事项 | 确认结果 |
| --- | --- |
| 站点资料 | `site_profile` 改为单行 `site_config`；已知的姓名、介绍、GitHub、邮箱用普通列，不整体塞入 JSON |
| 媒体 | `media_asset` 仅描述文件；`article_media` / `project_media` 描述用途，移除原三选一归属列 |
| 内容扩展 | `tag` 同时承载技术栈与文章主题；新增 `project_link`、`article_import_job` 覆盖确定的需求 |
| 关系完整性 | 服务端先校验合法性、唯一性和关联目标；数据库可建 `UNIQUE` 与指向主键的物理外键，统一 `ON DELETE RESTRICT`，不做级联；数据库约束不代替服务端校验或错误说明 |
| 主键和状态类型 | **所有 12 张表均用 `BIGINT UNSIGNED AUTO_INCREMENT` 的 `id` 作唯一主键**；状态、类别、角色及 0/1 开关统一用 `TINYINT UNSIGNED`，不使用 `BOOLEAN` 或 `VARCHAR` 存这些编码 |
| 暂不预建 | 全表 `extra JSON`、RBAC 角色权限表、自动链接巡检、图片去重、异步导入队列；未来确有需求再迁移 |

所有表的 `id` 均为 `BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY`，包括站点配置、媒体与四张关联表。关联列统一 `BIGINT UNSIGNED`；具体可建外键和唯一索引见下文。状态、类别、角色和 0/1 开关都用 `TINYINT UNSIGNED` 数值编码；可用值、必填性、长度、格式、关联目标和状态转换均由服务层先验证。不用 `CHECK` 承担业务规则；`UNIQUE`/外键用于数据完整性，但不能把数据库异常当作正常校验流程。`VARCHAR` 只用于名称、slug、URL 等文本，不承载枚举。时间统一 `DATETIME(3)`、应用写 UTC；文本统一 `utf8mb4` 并固定排序规则。接口可返回可读的状态名称，但数据库只存数字编码。

已发布的项目和文章首版不物理删除，只下架；首次发布后 slug 不修改。服务层先检查 slug 唯一并提供明确错误；数据库唯一索引仅维护最终完整性。草稿允许标题、摘要、slug 未齐；发布前由服务层统一验证。`published_at` 表示最近一次进入 `PUBLISHED` 的时间，下架后保留。

## 12 张表

### 1. `user_account`：账号与粗粒度类型

| 字段 | 类型/规则 |
| --- | --- |
| `id` | BIGINT UNSIGNED AUTO_INCREMENT PK；首版仅初始化站点主人一行 |
| `username` | VARCHAR(64) UNIQUE；服务层先校验非空、长度与唯一 |
| `type` | TINYINT UNSIGNED；0=OWNER，1=NORMAL；服务层校验取值 |
| `password_hash` | VARCHAR(255)；服务层要求强哈希非空 |
| `status` | TINYINT UNSIGNED；0=DISABLED，1=ENABLED；服务层初始化为 1 |
| `created_at`, `updated_at` | DATETIME(3)；服务层写入 |

`type` 只区分站主/普通账号，不承载细粒度权限。管理接口必须同时要求 `type = 0` 且 `status = 1`。普通访客类型已进入表结构，但首版不提供访客注册、登录、个人资料或需要账号才能阅读的功能；匿名阅读不生成账号。未来若开放访客账号，再补齐对应业务流程，不必改此列。

### 2. `site_config`：站点公开资料，单行

| 字段 | 类型/规则 |
| --- | --- |
| `id` | BIGINT UNSIGNED AUTO_INCREMENT PK；通过服务层只保留一行，不要求 ID 固定为 1 |
| `display_name` | VARCHAR(100)；服务层校验非空 |
| `headline` | VARCHAR(200)；服务层校验非空 |
| `intro` | TEXT；服务层校验非空 |
| `github_url` | VARCHAR(2048) NULL，仅 HTTP(S) |
| `email` | VARCHAR(254) NULL，非空时校验 |
| `avatar_media_id`, `resume_media_id` | BIGINT UNSIGNED NULL，分别 FK → `media_asset.id`；服务层先确认文件类型与可用状态 |
| `seo_title`, `seo_description` | VARCHAR(200) / VARCHAR(500) NULL |
| `updated_at` | DATETIME(3)；服务层写入 |

初始化已确认的昵称、简介、GitHub 和邮箱；头像及简历目前为空。未获批准的社交入口不存入公开配置。教育、证书尚无真实资料，不预建 JSON 或独立表（可以放在简历里）。

### 3. `project`：项目展示资料

| 字段 | 类型/规则 |
| --- | --- |
| `id` | BIGINT UNSIGNED AUTO_INCREMENT PK |
| `slug` | VARCHAR(160) NULL UNIQUE；服务层先校验唯一，发布前必填 |
| `title` | VARCHAR(200) NULL，发布前必填 |
| `summary`, `contribution` | TEXT NULL，发布前必填 |
| `outcome` | TEXT NULL；成果/可验证产出 |
| `time_label` | VARCHAR(100) NULL；展示用时间，不强迫不确定精度 |
| `status` | TINYINT UNSIGNED；0=DRAFT，1=PUBLISHED；服务层初始化为 0 |
| `is_featured` | TINYINT UNSIGNED；0=否，1=首页重点；服务层初始化为 0 |
| `sort_order` | INT；服务层初始化为 0 |
| `version` | BIGINT UNSIGNED；服务层初始化为 0，供乐观锁使用 |
| `published_at` | DATETIME(3) NULL |
| `created_at`, `updated_at` | DATETIME(3)；服务层写入 |

索引：`UNIQUE(slug)`、`(status, is_featured, sort_order, id)`、`(status, published_at, id)`；实施时按真实查询去重。封面通过 `project_media`；只存展示资料，不上传源码或整个项目目录。

### 4. `project_link`：项目外部入口

| 字段 | 类型/规则 |
| --- | --- |
| `id` | BIGINT UNSIGNED AUTO_INCREMENT PK |
| `project_id` | BIGINT UNSIGNED，FK → `project.id`；服务层先确认存在 |
| `link_type` | TINYINT UNSIGNED；0=REPOSITORY，1=DEMO，2=DOCS，3=OTHER |
| `label` | VARCHAR(100) NULL |
| `url` | VARCHAR(2048)；服务端要求非空且只接受 HTTP(S) |
| `is_visible` | TINYINT UNSIGNED；0=隐藏，1=展示；服务层初始化为 1 |
| `sort_order` | INT；服务层初始化为 0 |

索引：`(project_id, sort_order, id)`。首版不建自动探测链接可用性字段；站主发现失效可隐藏或修改。

### 5. `article`：唯一 Markdown 正文

| 字段 | 类型/规则 |
| --- | --- |
| `id` | BIGINT UNSIGNED AUTO_INCREMENT PK |
| `slug` | VARCHAR(160) NULL UNIQUE；服务层先校验唯一，发布前必填 |
| `title` | VARCHAR(200) NULL，发布前必填 |
| `summary` | TEXT NULL，发布前必填 |
| `body_markdown` | LONGTEXT；唯一可编辑正文真源，服务层校验非空 |
| `source_type` | TINYINT UNSIGNED；0=EDITOR，1=IMPORT_MARKDOWN；2=IMPORT_DOCX、3=IMPORT_PDF 仅预留，不表示首版支持导入 |
| `status` | TINYINT UNSIGNED；0=DRAFT，1=PUBLISHED；服务层初始化为 0 |
| `allow_visitor_download` | TINYINT UNSIGNED；0=不允许，1=允许；服务层初始化为 0 |
| `reading_time_minutes` | SMALLINT UNSIGNED；保存时由服务层重算 |
| `version` | BIGINT UNSIGNED；服务层初始化为 0，供乐观锁使用 |
| `published_at` | DATETIME(3) NULL |
| `created_at`, `updated_at` | DATETIME(3)；服务层写入 |

索引：`UNIQUE(slug)`、`(status, published_at, id)`。不另存 HTML 或 `word_count`；阅读时间足以支持列表展示。封面/正文图片在 `article_media`，主题在 `article_tag`。访客导出必须同时满足 `status = 1` 和 `allow_visitor_download = 1`，每次请求重新检查。

### 6. `article_import_job`：导入记录与问题

| 字段 | 类型/规则 |
| --- | --- |
| `id` | BIGINT UNSIGNED AUTO_INCREMENT PK |
| `article_id` | BIGINT UNSIGNED NULL，FK → `article.id`；解析失败前可为空，服务层先确认目标 |
| `source_type` | TINYINT UNSIGNED；0=MARKDOWN，1=DOCX、2=PDF 仅预留 |
| `original_filename` | VARCHAR(255) NULL |
| `status` | TINYINT UNSIGNED；0=FAILED，1=COMPLETED |
| `issues_json` | JSON NULL；问题码、引用的安全摘要、说明、是否阻止发布 |
| `created_at`, `updated_at` | DATETIME(3)；服务层写入 |

首版同步导入，不预建异步状态。`status = 1` 只表示生成了草稿，不代表文章可发布；可恢复问题与草稿一起保留。网络 URL 或错误详情不得保存凭据。索引：`(article_id, created_at)`。

### 7. `tag`：文章主题与技术栈

| 字段 | 类型/规则 |
| --- | --- |
| `id` | BIGINT UNSIGNED AUTO_INCREMENT PK |
| `kind` | TINYINT UNSIGNED；0=TOPIC，1=TECH；服务层校验取值 |
| `name` | VARCHAR(100)；服务层校验非空 |
| `normalized_name` | VARCHAR(100)；服务端统一大小写和空白规范 |
| `slug` | VARCHAR(120) UNIQUE；服务层先校验非空和唯一 |
| `group_code` | TINYINT UNSIGNED NULL；TECH 为 0=LANGUAGE、1=FRAMEWORK、2=TOOL、3=INFRA、4=DATA；TOPIC 时为空 |
| `logo_key` | VARCHAR(160) NULL；前端静态 Logo key，不接受任意网络 URL |
| `is_featured` | TINYINT UNSIGNED；0=否，1=首页技术展示；服务层初始化为 0 |
| `sort_order` | INT；服务层初始化为 0 |

服务层先校验 `(kind, normalized_name)` 与 `slug` 不重复；数据库建 `UNIQUE(kind, normalized_name)`、`UNIQUE(slug)` 与普通索引 `(kind, group_code, is_featured, sort_order)`。`TOPIC` 不设技术分组；`TECH` 可通过项目标签证明使用经历。已确认技术清单可作为初始数据；虚构项目与文章不得入正式库。

### 8–9. `article_tag`、`project_tag`：标签关联

| 表 | 字段与索引 |
| --- | --- |
| `article_tag` | `id` BIGINT UNSIGNED AUTO_INCREMENT PK；`article_id` BIGINT UNSIGNED FK → `article.id`，`tag_id` BIGINT UNSIGNED FK → `tag.id`；UNIQUE `(article_id, tag_id)`、反向索引 `(tag_id, article_id)`；服务层先校验目标与重复组合 |
| `project_tag` | `id` BIGINT UNSIGNED AUTO_INCREMENT PK；`project_id` BIGINT UNSIGNED FK → `project.id`，`tag_id` BIGINT UNSIGNED FK → `tag.id`；UNIQUE `(project_id, tag_id)`、反向索引 `(tag_id, project_id)`；服务层先校验目标与重复组合 |

服务端限制文章使用 `TOPIC`，项目可使用 `TOPIC` / `TECH`；公开筛选必须再过滤内容 `PUBLISHED`。

### 10. `media_asset`：文件与 OSS 来源元数据

| 字段 | 类型/规则 |
| --- | --- |
| `id` | BIGINT UNSIGNED AUTO_INCREMENT PK |
| `asset_type` | TINYINT UNSIGNED；0=IMAGE，1=DOCUMENT（简历预留）；服务层初始化为 0 |
| `storage_key` | VARCHAR(512) NULL UNIQUE；`READY` 时由服务层先要求非空、唯一，私有 OSS 对象键 |
| `source_type` | TINYINT UNSIGNED；0=LOCAL_NOTE，1=REMOTE_URL，2=EDITOR_UPLOAD |
| `source_host` | VARCHAR(255) NULL；仅域名，不含 token |
| `original_filename` | VARCHAR(255) NULL；仅展示/审计，不参与对象键生成 |
| `mime_type` | VARCHAR(100) NULL；`READY` 时由服务层要求非空 |
| `byte_size` | BIGINT UNSIGNED NULL；`READY` 时由服务层要求非空 |
| `sha256` | BINARY(32) NULL；`READY` 时由服务层要求非空 |
| `width`, `height` | INT UNSIGNED NULL；图片 READY 时由服务层要求非空 |
| `status` | TINYINT UNSIGNED；0=PENDING，1=READY，2=FAILED |
| `created_at`, `updated_at` | DATETIME(3)；服务层写入 |

索引：`(status, created_at)`、`UNIQUE(storage_key)`；首版不跨内容去重，不建 sha256 索引。服务层先校验对象键唯一；数据库唯一索引防止并发重复。MySQL 不存图片字节。媒体没有“公开”列；自增 ID 只用于定位，读取时必须根据使用关系重新授权，不向前端暴露 OSS 对象键。

### 11–12. `article_media`、`project_media`：文件在内容中的用途

| 表 | 字段与索引 |
| --- | --- |
| `article_media` | `id` BIGINT UNSIGNED AUTO_INCREMENT PK；`article_id` BIGINT UNSIGNED FK → `article.id`，`media_id` BIGINT UNSIGNED FK → `media_asset.id`，`role` TINYINT UNSIGNED (0=INLINE、1=COVER、2=OG_IMAGE)，`alt_text` VARCHAR(500) NULL，`source_reference` VARCHAR(2048) NULL，`sort_order` INT；UNIQUE `(article_id, media_id, role)`，索引 `(media_id, article_id)` |
| `project_media` | `id` BIGINT UNSIGNED AUTO_INCREMENT PK；`project_id` BIGINT UNSIGNED FK → `project.id`，`media_id` BIGINT UNSIGNED FK → `media_asset.id`，`role` TINYINT UNSIGNED（0=GALLERY、1=COVER），`alt_text` VARCHAR(500) NULL，`sort_order` INT；UNIQUE `(project_id, media_id, role)`，索引 `(media_id, project_id)` |

每篇文章或每个项目最多一个 `COVER`；由事务内的替换操作维护，并测试并发场景。Markdown 正文引用站内媒体 ID；`source_reference` 仅供导入问题定位，不作为浏览器加载地址。首版不复用同一媒体 ID 到不同内容，避免一处发布让另一处草稿附件意外公开。

## 服务端校验、事务与访问规则

1. 所有入口先在服务层校验必填、长度、格式、状态码、转换、唯一性、关联目标和权限，返回稳定的业务错误；不能把 MySQL 的唯一/外键异常当作正常校验流程。关联写入与主体修改放在同一事务；并发更新使用 `version` 或必要的行锁。涉及唯一性或关联替换的首版写事务，在完成外部图片处理后先锁定已初始化的 `site_config` 单行（`SELECT ... FOR UPDATE`，写事务使用 READ COMMITTED），再由服务层复查并写入；这会串行化低流量的站主管理写入，后续多写入者/高吞吐再细分锁。数据库异常仍须转换成安全、可理解的接口错误，不得透出 SQL。
2. 项目/文章通过 `status` 在 0(DRAFT) ↔ 1(PUBLISHED) 间切换；发布时检查必填资料、slug 和所有引用媒体 `status = 1`(READY)。下架保留正文、标签、媒体关系与最近发布时间。公开查询只返回 `status = 1`。
3. 匿名媒体读取仅当媒体被已发布文章/项目引用，或为 `site_config` 当前获准公开的头像/简历时允许；否则 `404`。站主可读自己的草稿媒体。**自增 ID 可被枚举，绝不作为授权依据。**
4. 草稿若未来允许物理删除，服务层在事务中先显式清理导入记录、链接、标签和媒体关系，再删除父记录；删除媒体前先检查所有关系表和站点配置均无引用。所有外键 `ON DELETE RESTRICT`，不使用级联替代显式清理。OSS 对象在数据库解除引用后做可重试的孤儿清理，不假装 OSS 与 MySQL 能原子提交。
5. 集成测试覆盖非法输入、不存在的目标 ID、重复 slug/关系、并发替换/下架、删除清理；验证服务层给出业务错误，而不是依赖 SQL 异常文本。迁移文件和数据库元数据核对预期唯一索引与物理外键，数据迁移后运行孤儿关联核对查询。

## 留待后续版本

不建 `extra JSON`、RBAC 角色权限表、自动链接巡检、图片哈希去重、异步导入队列、文章历史版本、Agent 会话/向量索引或缓存表。Word/PDF **导出**是首版功能，但不持久化导出文件；Word/PDF **导入**仅为未来适配器预留 `source_type` code。
