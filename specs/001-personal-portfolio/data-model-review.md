# data-model.md 严格评审报告

> 评审基线：`spec.md`（2026-09-25 Draft）与当前 `data-model.md`（Phase 1）
>
> 评审原则：优先保证需求覆盖、数据一致性、可扩展性、长期维护成本和数据库可约束性；不为了“未来可能”无边界建表，但对已经在 spec 中明确的扩展方向必须留出稳定接口。

## 结论

当前 `data-model.md` **可以作为概念草稿，但不建议直接作为建表/迁移依据**。

主要问题不是“字段少几个”，而是：

1. **需求覆盖不完整**：技术栈、文章封面、预计阅读时长、项目成果/时间信息、外部链接、Markdown 导入错误记录等需求没有完整落到模型。
2. **存在结构性反模式**：`media_asset` 通过 `article_id / project_id / profile_id` 三选一表达归属，属于多态外键，数据库层难以维护完整的引用约束。
3. **配置与业务数据边界混乱**：长期稳定、仅站长维护的个人简介、GitHub、邮箱等被单独抽成 `site_profile` 业务表，收益很低。
4. **扩展点不足**：核心实体普遍没有扩展 JSON；`source_kind`、`code_url/demo_url` 等结构对后续需求增长过于封闭。
6. **约束和索引描述不够**：唯一约束、外键删除策略、反向索引、状态字段语义、slug 不复用策略等仍不足以支持可靠实现。

综合评价：

| 维度 | 评价 | 说明 |
|---|---|---|
| 需求覆盖 | 5/10 | 主干实体齐全，但多项已明确需求未建模 |
| 数据一致性 | 5/10 | 发布状态规则较清楚，但媒体归属存在结构性问题 |
| 扩展性 | 4/10 | 缺少统一扩展字段，多个枚举/固定列过于封闭 |
| 可维护性 | 5/10 | 单 Markdown 模型是优点，但配置、媒体、导入职责边界需要重构 |
| 可实施性 | 6/10 | 足以继续讨论，但不宜直接生成 migration |

---


## 全局约束补充：所有关联均为逻辑外键，禁止物理外键

本项目所有实体之间的 `xxx_id` 关联 **均只作为逻辑外键使用，不在 MySQL 中创建任何 FOREIGN KEY 约束**。

也就是说：

```text
article_id → article.id
project_id → project.id
media_id   → media_asset.id
tag_id     → tag.id
```

只表达业务关联关系，不生成：

```sql
FOREIGN KEY (...)
REFERENCES ...
```

数据库层仅通过：

- 普通索引
- 唯一索引
- NOT NULL / CHECK（适用时）
- 应用层事务
- Service 层校验
- 删除前引用检查
- 关联数据显式清理

来保证一致性。

禁止使用物理外键的原因：

1. 降低表结构之间的强耦合；
2. 避免高并发写入、批量迁移和后续分库分表受到数据库 FK 限制；
3. 让业务一致性由应用服务显式控制，行为更可观测；
4. 方便未来拆分服务、异步化和独立演进；
5. 避免数据库级级联删除带来的隐式数据变更。

因此本文后续所有“外键”“FK”“CASCADE”等字样，均应理解为**逻辑关联设计**。实现时不得创建物理 FOREIGN KEY，也不得依赖数据库级 `ON DELETE CASCADE`。


# 一、对已发现三个问题的确认

## 1. 二值状态应使用 `BOOLEAN` 表达，而不是裸 `TINYINT`

这个判断成立。

当前：

```text
type    TINYINT (0管理员，1普通用户)
enabled TINYINT (0不可用，1可用)
```

其中 `enabled` 明确只有 true/false 两个语义，应写成：

```text
is_enabled BOOLEAN NOT NULL DEFAULT TRUE
```

需要说明：在 MySQL 中 `BOOLEAN` 底层仍是 `TINYINT(1)` 的同义表示，但在**数据模型、DDL 和代码语义层**使用 `BOOLEAN` 更清楚，可以避免“0/1 到底代表什么”的认知成本。

不过不能把所有“状态”都改成 Boolean。

例如：

```text
article.status = DRAFT / PUBLISHED
media.status   = PENDING / READY / FAILED
```

它们属于生命周期状态，未来天然可能出现 `ARCHIVED`、`PROCESSING` 等状态，应继续使用明确的多值状态字段，而不是拆成多个 Boolean。

**整改：**

- `enabled` → `is_enabled BOOLEAN`
- `allow_visitor_download` 保持 `BOOLEAN`
- 后续 `is_featured`、`is_visible` 等二值语义均使用 `BOOLEAN`
- `status` 一类生命周期字段仍使用字符串状态码/应用枚举

---

## 2. `site_profile` 属于过度建模，应合并进站点配置

当前 `site_profile` 只有一行，却单独维护：

- `display_name`
- `headline`
- `intro`
- `github_url`
- `email`
- `avatar_media_id`
- `resume_media_id`

这些内容具有共同特征：

- 只有站点主人可修改；
- 修改频率低；
- 不需要列表查询；
- 不参与复杂关系运算；
- 大部分属于站点级配置，而非独立业务实体。

因此单独建 `site_profile` 表价值不高。

推荐两种方式：

### 方案 A：配置文件

如果这些内容只有部署时修改：

```text
content/site-config.yaml
```

即可。

### 方案 B：统一 `site_config`

如果后续希望站长在管理端修改，则保留一张统一的站点配置表，而不是 `site_profile` 专表。

推荐：

```text
site_config
- profile_json
- contact_json
- seo_json
- avatar_media_id
- resume_media_id
- extra
- updated_at
```

这样教育经历、课程、证书、学习方向、简介、社交入口等低频配置都可以统一管理。

**结论：删除 `site_profile`，升级为通用 `site_config`。**

---

## 3. 核心实体缺少扩展 JSON

这个问题成立，而且当前模型中比较明显。

以下实体建议统一增加：

```text
extra JSON NULL
```

至少包括：

- `user`
- `site_config`
- `project`
- `project_link`
- `article`
- `tag`
- `media_asset`
- `article_import_job`

但必须明确：

> `extra` 是扩展逃生口，不是把已知核心字段全部塞 JSON 的理由。

例如以下字段已经是明确业务条件，就仍然应该建正式字段：

```text
article.status
article.published_at
project.slug
media_asset.mime_type
```

不能因为有 JSON 就改成无结构文档。

---

# 二、P0：必须在实施前修复的问题

## P0-1：当前模型没有完整覆盖 spec 已经明确的实体和字段

### 1. 技术栈没有数据模型

spec 明确要求首页展示技术栈，并按语言、框架/工具、基础设施、数据技术等类别组织，还允许技术与项目关联。

当前模型只有一个通用 `tag`：

```text
id / name / slug
```

无法表达：

- 标签是文章主题还是技术能力；
- Java 属于 Language，Spring 属于 Framework；
- 哪些技术需要在首页技术栈展示；
- 技术与项目之间的证据关联；
- Logo / 排序 / 分组。

**建议：扩展 `tag`：**

```text
kind        TOPIC / TECH
group_code  LANGUAGE / FRAMEWORK / TOOL / INFRA / DATA / ...
is_featured BOOLEAN
sort_order
extra JSON
```

继续复用 `project_tag` 关联项目，避免额外再建 `technology` 表。

---

### 2. `article` 缺少文章封面

spec 的 Key Entity 已明确文章包含封面。

当前 `article` 无 `cover_media_id`，而 `media_asset.article_id` 又不能区分：

- 正文图片
- 封面
- OpenGraph 图片

因此模型不足。

**建议：通过 `article_media.role` 区分：**

```text
INLINE
COVER
OG_IMAGE
```

---

### 3. 缺少“预计阅读时长”

博客列表验收要求展示预计阅读时长。

当前模型没有对应字段，也没有说明是否实时计算。

推荐在保存文章时计算并持久化：

```text
word_count INT UNSIGNED
reading_time_minutes SMALLINT UNSIGNED
```

原因：

- 博客列表无需每次重新解析 Markdown；
- 导入与站内编辑可以走同一计算逻辑；
- 值可以随保存重新生成，不成为人工维护字段。

---

### 4. `project` 缺少成果和时间信息

spec 对项目实体要求：

- 本人贡献
- 成果
- 时间信息
- 标签
- 图片
- 外部入口

当前只有：

```text
title
summary
contribution
```

没有成果和时间。

建议增加：

```text
outcome TEXT NULL
time_label VARCHAR(...) NULL
```

若以后确实需要按日期查询，再升级成结构化日期字段；Phase 1 没必要为不确定精度强行拆 `start_date/end_date`。

---

### 5. 外部链接实体没有真正落地

当前项目把外部链接写死为：

```text
code_url
demo_url
```

这在第一版能用，但与 spec 的“外部链接”实体不一致，也会很快遇到：

- 文档
- Release
- 在线 Demo
- 仓库
- API 文档
- 视频
- 其他链接

继续加列会演变成：

```text
code_url
demo_url
docs_url
video_url
xxx_url
```

**建议独立 `project_link` 表。**

---

## P0-2：`media_asset` 的多态归属设计不合格

当前：

```text
article_id
project_id
profile_id

三者恰好一个非空
```

这是典型的 polymorphic association。

主要问题：

1. 一个表同时指向三个不同父实体；
2. 很难通过普通外键完整表达“三者恰好一个”；
3. 新增 owner 类型必须继续加列；
4. `profile_id` 删除后还会进一步失去意义；
5. 封面/正文/头像/简历等“使用方式”与“二进制资源”混在一起；
6. 媒体的 `alt_text` 实际属于“引用上下文”，不一定属于文件本身。

应拆成：

```text
media_asset        只描述文件
article_media      文章如何使用文件
project_media      项目如何使用文件
site_config        显式引用头像/简历
```

例如：

```text
article_media
- article_id
- media_id
- role
- alt_text
- source_reference
- sort_order
```

这样 `media_asset` 不需要知道自己属于文章还是项目。

---

## P0-3：Markdown 导入错误没有可持久化的数据结构

spec 明确要求：

- 导入提供预览；
- 缺失图片要指出具体引用；
- 不支持内容必须明确提示；
- 后续 Word/PDF 继续复用同一导入流程。

当前 `data-model.md` 虽然文字写了：

> 若归档失败，记录具体受影响的引用

但数据库中根本没有字段或实体存放这些信息。

这是**文档内部自相矛盾**。

建议增加：

```text
article_import_job
- id
- article_id
- source_type
- original_filename
- status
- issues_json
- metadata_json
- created_at
- updated_at
- extra
```

其中：

```text
issues_json
```

可以记录：

```json
[
  {
    "code": "MISSING_LOCAL_IMAGE",
    "reference": "../img/a.png",
    "message": "引用图片未随导入提交"
  }
]
```

Phase 1 不必为了每条错误再建子表，JSON 足够。

---

# 三、P1：强烈建议修复的问题

## P1-1：`user.type` 可以保留，但应明确其业务边界

当前：

```text
type TINYINT (0管理员，1普通用户)
```

结合本项目实际需求，这个字段是可以成立的。

当前站点并不需要复杂 RBAC，账号类型只需要区分：

```text
OWNER / ADMIN
VISITOR / NORMAL
```

或等价的两类用户。

如果未来出现“与站点主人具有同等管理权限的人”，仍然可以复用同一管理员类型，不需要引入角色表、权限表或 RBAC 模型。

因此这里不应以 YAGNI 为理由删除 `type`。

### 建议调整

可以保留 `type`，但建议：

1. 明确它只是“用户类别”，不是权限系统；
2. 不在该字段上继续叠加细粒度权限含义；
3. 如果继续使用数值编码，必须在代码与文档中固定常量语义；
4. 如果未来仍然只有两类用户，则无需升级为 RBAC。

例如：

```text
0 = OWNER / ADMIN
1 = NORMAL / VISITOR
```

或者使用更易读的字符串 code。

结论：

> `user.type` 在当前业务边界下是足够且可维护的，应保留；只有当未来出现真正的多角色、多权限差异时，才需要重新设计权限模型。

## P1-2：`password` 命名不准确

应改：

```text
password_hash
```

数据库永远不应该让字段名暗示里面可以放密码明文。

---

## P1-3：`source_kind` 过度封闭

当前：

```text
EDITOR / MARKDOWN_IMPORT
```

spec 已明确未来可能扩展 Word / PDF 导入。

因此字段语义应该设计成可扩展 code：

```text
source_type VARCHAR(32)
```

Phase 1 值：

```text
EDITOR
IMPORT_MARKDOWN
```

后续直接加入：

```text
IMPORT_DOCX
IMPORT_PDF
```

不应因为增加一个导入适配器就改数据库结构。

如果实现使用 Java enum，可以由应用代码约束，但数据库不要使用难扩展的硬编码数值枚举。

---

## P1-4：`code_url/demo_url` 扩展性差

见前述 `project_link`。

这两个字段应移除。

---

## P1-5：媒体“文件属性”和“引用属性”混杂

当前：

```text
alt_text
```

位于 `media_asset`。

但 alt text 本质是“这张图在当前文章中的语义说明”。

即使 Phase 1 不共享媒体，也应该把语义放到：

```text
article_media.alt_text
project_media.alt_text
```

`media_asset` 只负责文件层元数据。

---

## P1-6：`slug` “唯一且不复用”没有真正形成约束规则

`UNIQUE(slug)` 只保证当前表中不存在重复记录。

如果物理删除一条记录，slug 就可以重新使用。

应明确：

- 已发布内容不物理删除；
- 首次发布后 slug 默认不可修改；
- 下架只改状态；
- 若未来支持删除，仍保留 tombstone 或 slug registry。

Phase 1 最简单的规则：

> 已发布过的 project/article 不执行物理删除，因此 UNIQUE(slug) 足够保证“不复用”。

---

## P1-7：`published_at` 语义不清

当前没有说明：

- 首次发布时间？
- 最近一次发布时间？
- 下架后是否清空？
- 再发布是否覆盖？

建议定义：

```text
published_at = 最近一次进入 PUBLISHED 状态的时间
```

如未来需要保留首次发布，再加：

```text
first_published_at
```

不要让业务代码各自解释。

---

## P1-8：连接表缺少反向查询索引说明

例如：

```text
article_tag PRIMARY KEY(article_id, tag_id)
```

能够高效：

```text
article -> tags
```

但访客点击标签要查询：

```text
tag -> articles
```

因此还需要：

```text
INDEX(tag_id, article_id)
```

`project_tag` 同理。

---

## P1-9：媒体 UUID 的数据库物理类型没有说明

“不预测 UUID”作为公开标识是合理的，但 MySQL 中如果直接：

```text
CHAR(36) PRIMARY KEY
```

会造成较大的索引和 B+Tree 成本。

建议数据库内部使用：

```text
BINARY(16)
```

API 层再转换为 UUID 字符串。

如果实现阶段认为复杂度不值得，也至少应明确选择，而不是只写“UUID 主键”。

---

## P1-10：核心表缺少明确的逻辑关联删除策略

例如：

- 删除文章后 `article_tag` 怎么处理？
- 删除项目后 `project_link` 怎么处理？
- 媒体仍被引用时能否删除？
- 删除 tag 后关联怎么处理？

建议由应用层显式定义删除规则，而不是依赖数据库级联：

```text
删除 article
→ 同事务/受控流程删除 article_tag、article_media、project-independent import relation

删除 project
→ 同事务/受控流程删除 project_tag、project_media、project_link
```

`media_asset` 在仍被任何逻辑关联引用时禁止直接删除；应先由业务层解除引用，再进入孤儿资源清理流程。

**不得创建物理 FOREIGN KEY，也不得使用 `ON DELETE CASCADE`。**

---

# 四、P2：规范和长期维护问题

## P2-1：命名不统一

当前同时存在：

```text
type
enabled
status
source_kind
```

建议统一语义：

```text
is_xxx       二值
xxx_type     分类
xxx_status   生命周期
```

例如：

```text
is_enabled
source_type
status
```

---

## P2-2：`tag.name` 的唯一语义应更明确

“通过比较规则防止仅大小写不同的重复标签”方向正确，但应该明确实现策略。

推荐：

```text
normalized_name
```

保存标准化结果，并增加唯一约束：

```text
UNIQUE(kind, normalized_name)
UNIQUE(slug)
```

这样不会依赖数据库默认 collation 的偶然行为。

---

## P2-3：时间字段建议统一精度

建议：

```text
DATETIME(3)
```

数据库统一存 UTC。

这样日志、导入任务、并发保存等场景更容易对齐毫秒级时间。

---

## P2-4：项目也建议有 `version`

文章已有：

```text
version
```

用于避免覆盖较新编辑，这是好设计。

项目虽然只有单站长，但仍可能发生：

- 两个浏览器标签页；
- 自动保存；
- 管理端并发请求。

建议 `project` 同样增加 `version BIGINT UNSIGNED`。

---

# 五、当前模型值得保留的部分

严格评审不等于全部推倒。

以下设计建议继续保留：

## 1. 文章只有一份 Markdown 正文

```text
body_markdown
```

是正确设计。

不维护 HTML、富文本 JSON、Markdown 三套源，可以显著降低双向转换和数据漂移。

## 2. 草稿/发布状态明确

```text
DRAFT ↔ PUBLISHED
```

满足首版需求，也比单纯 `is_published` 更利于未来扩展。

## 3. `allow_visitor_download` 独立于发布状态

这是正确的业务建模。

公开阅读与公开下载是两种权限。

## 4. `article.version`

用于乐观锁和未来 Agent 索引失效，是当前模型中比较成熟的字段。

## 5. 不提前建立 Agent/向量/缓存表

spec 明确第二版才考虑 Agent。

当前“不建 Agent 会话、向量索引、缓存失效表”的边界是合理的，不应为了未来 AI 功能污染 Phase 1。

---

# 六、建议的模型重构优先级

## 第一批：建表前必须完成

1. 删除 `site_profile`，改 `site_config`
2. `enabled` → `is_enabled BOOLEAN`
4. `password` → `password_hash`
5. 增加核心实体 `extra JSON`
6. 拆分 `media_asset` 与内容引用关系
7. 增加 `article_import_job`
8. 增加 `project_link`
9. 补齐 article cover / read time
10. 补齐 project outcome / time
11. tag 增加 TOPIC / TECH 类型与技术分组能力

## 第二批：migration 设计时完成

1. FK
2. CASCADE / RESTRICT 策略
3. 索引
4. CHECK
5. 字段长度
6. UUID 物理存储方式
7. slug 不复用规则
8. published_at 语义
9. optimistic lock

---

# 七、最终判断

当前 `data-model.md` 最大的问题不是“数据库不够规范”，而是：

> **它已经开始替实现做决定，却还没有完整承接 spec。**

典型表现：

```text
site_profile 被独立建表
media ownership 被固定成三种 owner
source_kind 只写两个来源
code_url/demo_url 被固定成两列
```

与此同时 spec 已经明确的：

```text
技术栈关联
文章封面
阅读时长
导入失败问题
未来导入适配
项目成果
外部链接
```

又没有完整建模。

正确方向应当是：

> **对已经确定的业务语义做结构化建模；对尚未确定的细节保留 JSON/接口扩展点；对长期固定站点内容统一放配置层；不要让数据库绑定尚未确定的基础设施。**

重构后的推荐模型见 `data-model-upgrade.md`。
