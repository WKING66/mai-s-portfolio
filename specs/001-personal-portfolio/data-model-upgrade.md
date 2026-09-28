# 数据模型升级方案（Phase 1）

> 基于 `spec.md` 与 `data-model.md` 严格评审后的建议版本。
>
> 目标数据库：MySQL 8.4 LTS。
>
> 本文仍是逻辑模型，不是最终 migration SQL；字段长度、CHECK 语句、外键名等可在 migration 阶段确定。


## 0. 全局数据库关联约束

**本项目禁止使用物理外键。**

本文所有：

```text
xxx_id → target.id
```

以及文中出现的“关联”“FK”语义，均表示**逻辑外键 / 逻辑关联**，只用于描述实体关系。

MySQL migration 中不得生成：

```sql
FOREIGN KEY (...)
REFERENCES ...
ON DELETE CASCADE
ON DELETE RESTRICT
```

关系完整性统一由应用层维护，包括：

- 写入前验证被引用记录是否存在；
- 关键写操作放在事务中；
- 删除前检查是否存在被引用关系；
- 需要级联清理时由 Service 层显式删除；
- 所有关联 ID 建立必要普通索引；
- 对关键关系增加唯一索引或 CHECK（适用时）；
- 对孤儿媒体、失效关联等通过定时任务或维护任务兜底清理。

这样可以降低数据库层强耦合，为后续服务拆分、分库分表、异步化和独立部署保留空间。


## 1. 设计原则

1. 所有时间统一存 UTC，推荐 `DATETIME(3)`。
2. 二值语义统一使用 `BOOLEAN`。
3. 生命周期使用明确的状态码，不使用多个 Boolean 拼状态。
4. 已知、需要查询/约束的业务字段必须结构化；未知扩展使用 `extra JSON`。
5. `extra JSON` 是扩展口，不替代主字段。
6. 长期稳定、低频、仅站长维护的站点资料统一进入 `site_config`，不再维护 `site_profile`。
7. 媒体文件本身与“文章/项目如何引用媒体”分离。
8. `storage_key` 作为对象存储逻辑 key 使用；文档可用 OSS 举例，但不得依赖特定厂商专有语义。
9. Markdown 是文章唯一可编辑正文源。
10. 所有关联均使用逻辑外键，禁止创建物理 FOREIGN KEY。
11. Phase 1 不创建 Agent、向量索引、评论、访客账号等表。

---

# 2. 实体总览

```text
user

site_config
  ├─ avatar_media_id ───────→ media_asset
  └─ resume_media_id ───────→ media_asset

project
  ├─ 1..n project_link
  ├─ n..m tag       via project_tag
  └─ 0..n media_asset via project_media

article
  ├─ n..m tag       via article_tag
  ├─ 0..n media_asset via article_media
  └─ 0..n article_import_job

tag
  ├─ TOPIC
  └─ TECH
```

---

# 3. `user`（站点管理账号）

Phase 1 仍只允许一个站点主人账号。

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `id` | BIGINT UNSIGNED PK | 内部稳定 ID |
| `username` | VARCHAR，UNIQUE，NOT NULL | 登录名 |
| `type` | TINYINT，NOT NULL | 用户类别：0=管理员/站点维护者，1=普通用户/访客类型 |
| `password_hash` | VARCHAR，NOT NULL | 安全密码哈希 |
| `is_enabled` | BOOLEAN，NOT NULL，DEFAULT TRUE | 是否允许管理端登录 |
| `created_at` | DATETIME(3)，NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3)，NOT NULL | 更新时间 |
| `extra` | JSON，可空 | 非认证核心的未来扩展 |

### 约束

- `type` 仅表达粗粒度用户类别，不承担细粒度权限控制。
- 当前仅区分管理员/站点维护者与普通用户两类。
- 若未来仍只有同权维护者，则继续复用管理员类型即可，无需引入 RBAC。
- 密码字段只允许存哈希。

---

# 4. `site_config`（站点级配置，单行）

替代原 `site_profile`。

如果未来确定所有个人资料只能随部署修改，可以进一步把部分 JSON 移入配置文件；当前保留表结构是为了支持站长在管理端修改头像、简介、联系方式等内容。

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `id` | BIGINT PK，固定单行 | 站点配置主体 |
| `profile_json` | JSON，NOT NULL | 姓名/品牌名、headline、简介、学习方向、兴趣、做事方式、教育、课程、证书、成果等 |
| `contact_json` | JSON，NOT NULL | 已批准公开的 GitHub、邮箱和其他联系入口 |
| `seo_json` | JSON，可空 | 默认站点标题、描述等全站 SEO 配置 |
| `avatar_media_id` | BINARY(16) / UUID，可空逻辑关联 | 当前头像 |
| `resume_media_id` | BINARY(16) / UUID，可空逻辑关联 | 当前简历 |
| `updated_at` | DATETIME(3)，NOT NULL | 更新时间 |
| `extra` | JSON，可空 | 未来站点级配置 |

### `profile_json` 示例

```json
{
  "displayName": "阿霾",
  "headline": "学生 / 开发者",
  "intro": "...",
  "learningDirections": ["AI Agent", "Backend"],
  "interests": [],
  "education": [],
  "courses": [],
  "certificates": [],
  "achievements": []
}
```

### `contact_json` 示例

```json
{
  "github": "https://github.com/...",
  "email": "...",
  "links": []
}
```

约定：

- JSON 中只保存站长已经批准公开的数据。
- 缺少简历时 `resume_media_id = NULL`。
- 不需要单独的“是否公开 GitHub”字段：未批准的入口不进入 public config。

---

# 5. `project`（项目展示）

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `id` | BIGINT UNSIGNED PK | 稳定内部 ID |
| `slug` | VARCHAR，UNIQUE，NOT NULL | 稳定公开引用 |
| `title` | VARCHAR，发布前非空 | 项目标题 |
| `summary` | TEXT，发布前非空 | 项目摘要 |
| `contribution` | TEXT，发布前非空 | 本人职责/贡献 |
| `outcome` | TEXT，可空 | 项目成果、结果或可验证产出 |
| `time_label` | VARCHAR，可空 | 展示用时间信息，如 `2026.03 - 2026.06` |
| `status` | VARCHAR 状态码 | `DRAFT` / `PUBLISHED` |
| `is_featured` | BOOLEAN，DEFAULT FALSE | 是否作为首页代表项目 |
| `sort_order` | INT，DEFAULT 0 | 展示顺序 |
| `version` | BIGINT UNSIGNED，NOT NULL | 乐观锁版本 |
| `published_at` | DATETIME(3)，可空 | 最近一次发布时刻 |
| `created_at` | DATETIME(3)，NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3)，NOT NULL | 更新时间 |
| `extra` | JSON，可空 | 未来扩展 |

### 说明

- 删除原 `code_url`、`demo_url`，改为 `project_link`。
- 删除原 `cover_media_id`，媒体角色由 `project_media` 统一描述。
- 已发布项目不做物理删除，确保 slug 不复用。
- 第一次发布后 slug 默认不可修改。

### 推荐索引

```text
UNIQUE(slug)
INDEX(status, sort_order)
INDEX(status, published_at)
INDEX(is_featured, status, sort_order)
```

---

# 6. `project_link`（项目外部入口）

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `id` | BIGINT UNSIGNED PK | 链接 ID |
| `project_id` | BIGINT UNSIGNED，NOT NULL，逻辑关联 | 所属项目 |
| `link_type` | VARCHAR | `REPOSITORY` / `DEMO` / `DOCS` / `VIDEO` / `OTHER` |
| `label` | VARCHAR，可空 | 展示文案 |
| `url` | VARCHAR，NOT NULL | HTTP(S) 地址 |
| `is_visible` | BOOLEAN，DEFAULT TRUE | 是否展示 |
| `availability_status` | VARCHAR | `UNKNOWN` / `AVAILABLE` / `UNAVAILABLE` |
| `sort_order` | INT，DEFAULT 0 | 展示顺序 |
| `last_checked_at` | DATETIME(3)，可空 | 最近确认可用时间 |
| `extra` | JSON，可空 | 后续扩展 |

### 逻辑关联

```text
project_link.project_id → project.id
```

仅建立普通索引，不创建物理外键。

删除项目时，由 Service 层在同一事务或受控清理流程中显式删除对应 `project_link` 记录。

Phase 1 不要求自动定时检查链接；`availability_status` 可以由管理端或后续检查任务更新。

---

# 7. `article`（博客文章）

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `id` | BIGINT UNSIGNED PK | 稳定内部 ID |
| `slug` | VARCHAR，UNIQUE，NOT NULL | 规范地址 |
| `title` | VARCHAR，发布前非空 | 标题 |
| `summary` | TEXT，发布前非空 | 博客列表与 SEO 描述 |
| `body_markdown` | LONGTEXT，NOT NULL | 唯一可编辑正文 |
| `source_type` | VARCHAR(32)，NOT NULL | `EDITOR` / `IMPORT_MARKDOWN`，后续可增加 DOCX/PDF |
| `status` | VARCHAR 状态码 | `DRAFT` / `PUBLISHED` |
| `allow_visitor_download` | BOOLEAN，NOT NULL，DEFAULT FALSE | 是否允许访客导出 |
| `word_count` | INT UNSIGNED，NOT NULL，DEFAULT 0 | 保存时计算 |
| `reading_time_minutes` | SMALLINT UNSIGNED，NOT NULL，DEFAULT 0 | 保存时计算 |
| `version` | BIGINT UNSIGNED，NOT NULL | 乐观锁 + Agent 索引版本依据 |
| `published_at` | DATETIME(3)，可空 | 最近一次进入 PUBLISHED 的时间 |
| `created_at` | DATETIME(3)，NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3)，NOT NULL | 更新时间 |
| `extra` | JSON，可空 | 未来扩展 |

### 说明

- Markdown 为唯一正文真源。
- 预览 HTML 不持久化为第二正文。
- 封面通过 `article_media.role = COVER` 表示。
- 主题通过 `article_tag` 表示。
- 阅读时长由后端按统一算法在保存时计算。
- 下架仅修改状态，不删除正文、标签、媒体关系。
- 已发布文章不做物理删除；首次发布后 slug 默认不可修改。

### 推荐索引

```text
UNIQUE(slug)
INDEX(status, published_at)
INDEX(updated_at)
```

---

# 8. `article_import_job`（文章导入任务）

用于 Markdown 导入，同时为未来 Word/PDF 适配保留稳定接口。

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `id` | BIGINT UNSIGNED PK | 导入任务 ID |
| `article_id` | BIGINT UNSIGNED，可空，逻辑关联 | 成功生成的草稿文章 |
| `source_type` | VARCHAR(32)，NOT NULL | `MARKDOWN`，后续 `DOCX` / `PDF` |
| `original_filename` | VARCHAR，可空 | 原始文件名 |
| `status` | VARCHAR | `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` |
| `issues_json` | JSON，可空 | 缺图、格式错误、网络图片归档失败等问题 |
| `metadata_json` | JSON，可空 | 导入适配器输出的元信息 |
| `created_at` | DATETIME(3)，NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3)，NOT NULL | 更新时间 |
| `extra` | JSON，可空 | 后续导入扩展 |

### `issues_json` 示例

```json
[
  {
    "code": "MISSING_LOCAL_IMAGE",
    "reference": "../assets/a.png",
    "message": "引用图片未提交"
  },
  {
    "code": "REMOTE_IMAGE_REJECTED",
    "reference": "https://example.com/a.png",
    "message": "远程资源校验失败"
  }
]
```

这样管理端可以准确展示“哪一条引用失败”，而不是只有一个总的 FAILED 状态。

---

# 9. `tag`（主题 / 技术能力标签）

统一承载：

- 文章主题
- 项目能力标签
- 首页技术栈

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `id` | BIGINT UNSIGNED PK | 标签 ID |
| `name` | VARCHAR，NOT NULL | 展示名称 |
| `normalized_name` | VARCHAR，NOT NULL | 去空白、标准化大小写后的唯一比较值 |
| `slug` | VARCHAR，UNIQUE，NOT NULL | 公开筛选标识 |
| `kind` | VARCHAR | `TOPIC` / `TECH` |
| `group_code` | VARCHAR，可空 | TECH 时：`LANGUAGE` / `FRAMEWORK` / `TOOL` / `INFRA` / `DATA` 等 |
| `is_featured` | BOOLEAN，DEFAULT FALSE | 是否在首页技术栈/重点主题展示 |
| `sort_order` | INT，DEFAULT 0 | 展示顺序 |
| `extra` | JSON，可空 | Logo key、展示别名等扩展 |

### 唯一约束

```text
UNIQUE(slug)
UNIQUE(kind, normalized_name)
```

技术 Logo 若使用前端静态资源，可在 `extra` 中保存稳定资源 key；如果以后需要后台上传 Logo，再升级为 `media_id`。

---

# 10. `article_tag`

| 字段 | 类型/规则 |
|---|---|
| `article_id` | BIGINT UNSIGNED，逻辑关联 → article.id |
| `tag_id` | BIGINT UNSIGNED，逻辑关联 → tag.id |

### 约束

```text
PRIMARY KEY(article_id, tag_id)
INDEX(tag_id, article_id)
```

删除文章或 tag 时，由应用层显式删除对应关联记录；不得依赖数据库 `ON DELETE CASCADE`。

公开标签筛选必须额外过滤：

```text
article.status = PUBLISHED
```

---

# 11. `project_tag`

| 字段 | 类型/规则 |
|---|---|
| `project_id` | BIGINT UNSIGNED，逻辑关联 → project.id |
| `tag_id` | BIGINT UNSIGNED，逻辑关联 → tag.id |

### 约束

```text
PRIMARY KEY(project_id, tag_id)
INDEX(tag_id, project_id)
```

项目可通过 `TECH` 标签与首页技术栈形成证据关联。

---

# 12. `media_asset`（文件资源本体）

`media_asset` 只描述一个受控文件，不再记录文章/项目/profile owner。

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `id` | UUID；DB 推荐 BINARY(16) PK | 不可预测公开标识 |
| `asset_type` | VARCHAR | `IMAGE` / `DOCUMENT` |
| `storage_key` | VARCHAR，READY 时非空 | 对象存储中的逻辑对象键；可用于 OSS/S3/MinIO/COS 等实现 |
| `source_type` | VARCHAR | `LOCAL_NOTE` / `REMOTE_URL` / `EDITOR_UPLOAD` / `SYSTEM` |
| `original_filename` | VARCHAR，可空 | 用户上传时的原文件名，仅展示/审计使用 |
| `source_host` | VARCHAR，可空 | 网络来源域名，不保存敏感 token |
| `mime_type` | VARCHAR，NOT NULL | 服务端校验后的 MIME |
| `byte_size` | BIGINT UNSIGNED，NOT NULL | 字节大小 |
| `sha256` | CHAR(64) / BINARY(32)，NOT NULL | 内容校验 |
| `width` | INT UNSIGNED，可空 | 图片宽度 |
| `height` | INT UNSIGNED，可空 | 图片高度 |
| `status` | VARCHAR | `PENDING` / `READY` / `FAILED` |
| `created_at` | DATETIME(3)，NOT NULL | 创建时间 |
| `updated_at` | DATETIME(3)，NOT NULL | 更新时间 |
| `extra` | JSON，可空 | 编码、归档等扩展信息 |

### 推荐索引

```text
UNIQUE(storage_key) WHERE 逻辑上 READY 必须唯一
INDEX(status, created_at)
INDEX(sha256)
```

MySQL migration 阶段需要按实际 DDL 能力实现相应唯一约束。

---

# 13. `article_media`（文章媒体引用）

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `article_id` | BIGINT UNSIGNED，逻辑关联 | 所属文章 |
| `media_id` | UUID，逻辑关联 | 文件 |
| `role` | VARCHAR | `INLINE` / `COVER` / `OG_IMAGE` |
| `alt_text` | VARCHAR，可空 | 当前文章语境中的替代文本 |
| `source_reference` | VARCHAR，可空 | 原 Markdown 引用路径，用于导入问题定位 |
| `sort_order` | INT，DEFAULT 0 | 顺序 |
| `extra` | JSON，可空 | 后续扩展 |

### 约束

```text
PRIMARY KEY(article_id, media_id, role)
INDEX(media_id)
```

业务层保证一篇文章最多一个 `COVER`。

---

# 14. `project_media`（项目媒体引用）

| 字段 | 类型/规则 | 用途 |
|---|---|---|
| `project_id` | BIGINT UNSIGNED，逻辑关联 | 所属项目 |
| `media_id` | UUID，逻辑关联 | 文件 |
| `role` | VARCHAR | `COVER` / `GALLERY` |
| `alt_text` | VARCHAR，可空 | 项目上下文替代文本 |
| `sort_order` | INT，DEFAULT 0 | 展示顺序 |
| `extra` | JSON，可空 | 后续扩展 |

### 约束

```text
PRIMARY KEY(project_id, media_id, role)
INDEX(media_id)
```

业务层保证一个项目最多一个 `COVER`。

---


# 14.1 逻辑关联一致性规则

所有关联字段只建立普通索引，不创建物理 FOREIGN KEY。

应用层必须保证：

```text
新增关联
→ 先校验目标记录存在

删除父记录
→ 先处理关联表/子记录，再处理父记录

删除媒体
→ 先确认 article_media / project_media / site_config 均无引用

批量删除
→ 必须通过显式 Service 流程执行，不允许依赖数据库级联
```

建议将这些规则纳入：

- Service 层事务；
- Repository 存在性校验；
- 集成测试；
- 数据一致性巡检任务。


# 15. 状态规则

## 文章 / 项目

```text
DRAFT → PUBLISHED
PUBLISHED → DRAFT
```

约定：

- `published_at` 表示最近一次进入 `PUBLISHED` 的时间；
- 下架时不删除正文、项目资料、标签或媒体关系；
- 已发布过的内容不物理删除；
- 首次发布后 slug 不再修改；
- 只有 PUBLISHED 内容进入公开 API、站点地图和未来 Agent 索引。

## 媒体

```text
PENDING → READY
PENDING → FAILED
FAILED  → PENDING   # 显式重试时允许
```

文章/项目发布前，所有实际引用的媒体必须 READY。

## 导入任务

```text
PENDING → PROCESSING → COMPLETED
                     ↘ FAILED
```

即使导入存在可恢复问题，也可以生成 DRAFT 文章，同时在 `issues_json` 中记录问题；存在阻止发布的问题时不得自动发布。

---

# 16. 公开权限规则

## 文章

公开阅读：

```text
status = PUBLISHED
```

访客导出：

```text
status = PUBLISHED
AND allow_visitor_download = TRUE
```

站点主人：

```text
草稿和已发布文章均可导出
```

## 项目

```text
status = PUBLISHED
```

## 媒体

不能因为拿到 UUID 就直接认为公开。

匿名访问媒体时必须检查：

- 是否被 PUBLISHED article 引用；
- 或是否被 PUBLISHED project 引用；
- 或是否为 `site_config` 当前公开头像/简历且对应入口允许公开。

推荐媒体访问授权始终在服务端重新判断，不能只依赖前端隐藏按钮。

---

# 17. 建议索引汇总

> 下列关联索引用于支持逻辑外键查询与清理；**不会创建任何物理 FOREIGN KEY**。

```text
user
- UNIQUE(username)

project
- UNIQUE(slug)
- INDEX(status, sort_order)
- INDEX(status, published_at)
- INDEX(is_featured, status, sort_order)

project_link
- INDEX(project_id, sort_order)
- INDEX(project_id, link_type)

article
- UNIQUE(slug)
- INDEX(status, published_at)
- INDEX(updated_at)

tag
- UNIQUE(slug)
- UNIQUE(kind, normalized_name)
- INDEX(kind, is_featured, sort_order)

article_tag
- PK(article_id, tag_id)
- INDEX(tag_id, article_id)

project_tag
- PK(project_id, tag_id)
- INDEX(tag_id, project_id)

media_asset
- INDEX(status, created_at)
- INDEX(sha256)

article_media
- PK(article_id, media_id, role)
- INDEX(media_id)

project_media
- PK(project_id, media_id, role)
- INDEX(media_id)

article_import_job
- INDEX(article_id)
- INDEX(status, created_at)
```

---

# 18. 不进入 Phase 1 的表

继续不建立：

- Agent 会话
- Prompt
- 向量索引
- 评论
- 访客账号
- 订阅
- 缓存失效
- 全文搜索索引
- 文章历史版本表
- 导出文件缓存表

第二版 Agent 仍可以根据：

```text
project.id
project.status
project.updated_at

article.id
article.status
article.version
article.updated_at
```

构建独立只读索引。

---

# 19. 从当前模型迁移到升级模型

> migration 只创建字段、索引、唯一约束及必要 CHECK；**严禁生成 FOREIGN KEY / REFERENCES / ON DELETE CASCADE**。

```text
user.password
    → user.password_hash

user.enabled TINYINT
    → user.is_enabled BOOLEAN

site_profile
    → site_config

project.code_url / demo_url
    → project_link

project.cover_media_id
    → project_media(role=COVER)

article
    → 增加 word_count / reading_time_minutes / extra

article.source_kind
    → article.source_type

media_asset.article_id/project_id/profile_id
    → 删除

media_asset.alt_text
    → article_media.alt_text / project_media.alt_text

media_asset.storage_key
    → 保持为对象存储逻辑 key；OSS 仅作为实现示例，不构成模型绑定

新增
    → article_import_job
    → article_media
    → project_media
    → project_link

tag
    → 增加 kind / group_code / is_featured / sort_order / extra
```

---

# 20. 最终推荐关系图

```text
                    ┌──────────────┐
                    │     user     │
                    └──────────────┘

                    ┌──────────────┐
                    │ site_config  │
                    └──────┬───────┘
                           │ avatar / resume
                           ▼
                    ┌──────────────┐
                    │ media_asset  │
                    └──────▲───────┘
                         ▲  │  ▲
                         │  │  │
             ┌───────────┘  │  └────────────┐
             │              │               │
      project_media    article_media        │
             │              │               │
             ▼              ▼               │
       ┌──────────┐    ┌──────────┐         │
       │ project  │    │ article  │         │
       └────┬─────┘    └────┬─────┘         │
            │               │               │
      project_link     article_import_job   │
            │                               │
            └──── project_tag      article_tag
                     │                 │
                     └───────┬─────────┘
                             ▼
                           tag
                    (TOPIC / TECH)
```

这个版本的核心思想是：

> **站点配置统一管理；文章和项目保持关系型核心字段；媒体作为独立资源；媒体“如何被使用”由关联表表达；导入流程有独立状态；未知未来需求通过 JSON 扩展，但已知业务约束不逃进 JSON。**
