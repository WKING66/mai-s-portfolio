# Implementation Plan: 个人作品集网站（首版）

> **架构修订中**：数据层以 2026-09-27 的 PostgreSQL + 统一 Document 决策及 [迁移审核点](postgresql-document-transition.md) 为准。本地开发后端已切到 PostgreSQL；不得依照下文残留的 MySQL/Article 单正文步骤开发博客。

**Branch**: `001-personal-portfolio` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

**Input**: 既有功能规格与站点主人明确的技术约束：前后端分离、Nuxt 服务端渲染、PostgreSQL 替换 MySQL、本地与网络图片统一归档到阿里云 OSS、SEO/GEO、第二版再增加访客 Agent。服务器、域名及正式部署待本地首版跑通后决定。

## Summary

用 Nuxt 作为独立前端进程，服务端渲染公开首页、项目列表、博客列表与文章；Spring Boot 作为独立后端进程，统一负责认证、内容管理、Markdown 导入与导出、媒体处理、权限判断及 Swagger/OpenAPI 接口文档。PostgreSQL 保存全站关系型数据、DocumentVersion 的唯一 Markdown 真源与资源元数据；阿里云 OSS 保存二进制 Asset。BlogPost 仅指向显式发布的版本，草稿编辑不改变公开内容。首版只实现 Markdown Importer/Exporter 并预留其他格式接口；第二版 Agent 检索必须按当前公开版本或单独批准的知识用途过滤。

## Technical Context

**Language/Version**: Java 21；TypeScript，Node.js 24 LTS。选用 Nuxt 4、Spring Boot 4.1；开发主库已切为 PostgreSQL 18.6。旧 MySQL 8.0.34/13306 保留供迁移核对与恢复，不再作为运行库。

**Primary Dependencies**: Nuxt/Vue、Tailwind CSS 4（通过官方 `@tailwindcss/vite` 插件接入）和 Nuxt 内置数据获取能力；Spring Web MVC、Sa-Token、MyBatis-Plus（分页插件）、PostgreSQL JDBC 驱动、Flyway PostgreSQL 模块、Druid、Bean Validation、Lombok、Spring Crypto、springdoc-openapi 3.x Web MVC UI。MySQL 运行依赖已移除。OSS SDK 与 Markdown 解析库在对应功能实现前验证后引入；DOCX/PDF 转换库不属于首版。暂不加 Axios、Hutool 或组件库。

**2026-09-26 确认**: Sa-Token、MyBatis-Plus、Druid 是站点主人确认的首选；具体版本先做 Boot 4 组合启动与会话/CSRF/分页实测。用户要求未来 Sa-Token Redis 接入兼容 Redis **服务端 3.2.1**，但首版不启用 Redis；该旧版本的真实适配和运行安全需另设门槛，不据此降低首版组件安全性。首页首屏之后沿用 A 版先展示技术栈，再展示项目和博客；排期仅为预估，开发按质量门槛下最快可验证进度推进。

**2026-09-26 编码与接口决策**: 以 [Spring Boot + Nuxt 代码规范](springboot-nuxt-code-style.md) 为评审门槛。后端按明确三层组织：`controller → service → mapper`，`entity`、`dto`、`enums`、`config`、`common` 仅提供配套类型；Controller/拦截器不直接访问 Mapper，业务层不得用 JdbcTemplate 替代已确认的 MyBatis-Plus。Service 必须先定义接口，具体逻辑由 `service/impl/*Impl` 实现；启动 Runner 调用接口。用户可见的响应、错误和状态提示集中在命名常量中，不散落在业务代码。所有 JSON 成功与失败响应采用统一 `{code,message,data,details}` 外壳，文件字节下载除外；同步修改 [HTTP 合同](contracts/http-api.md)、前端 API 层、OpenAPI 和测试。站点主人明确禁用 ponytail，后续开发不得启用该技能。

**Storage**: PostgreSQL 保存账号、资料、项目、统一 Document、不可变 DocumentVersion、BlogPost、标签和 Asset 元数据；首个基线草案为 15 张表，全部使用自增 BIGINT `id` 主键，状态/类别/开关用 SMALLINT 编码。业务校验先在服务层完成；唯一索引及物理外键维护完整性，外键禁止级联删除。OSS 私有 Bucket 存二进制字节，READY Asset 不原地覆盖。Markdown 正文只保存在 DocumentVersion，博客和 RAG 不另存第二真源。

**Testing**: Java 单元/接口测试、PostgreSQL 迁移及集成测试；Nuxt 组件与页面测试；浏览器端到端验收。重点覆盖 v1 发布后保存 v2 不公开、显式重新发布、版本标签/Asset 快照、登录后的下载许可、受控媒体授权、Markdown 导入导出、自增主键与 SMALLINT 状态、服务层先行校验和 OpenAPI 一致性。第二阶段另验收公开 Agent 不能检索草稿。

**Target Platform**: 首先在本机运行 Nuxt、Spring Boot 和 PostgreSQL；旧 MySQL 开发库只留作恢复来源。不得启动或修改本机 Docker。Redis 仅用于未来 Sa-Token 兼容验证，首版不依赖它。正式服务器、域名、HTTPS 终止和部署流水线在本地验收后决定。中国内地访客是主要受众。

**Project Type**: 前后端分离的内容管理型网站；两个应用进程，共享一份 HTTP 合同，不拆微服务。

**Performance Goals**: 延续规格的普通移动网络 3 秒内可读首屏、360/768/1440px 无横向阅读溢出；公开页的核心文字出现在初始 HTML 中。无未测量的吞吐量或搜索排名承诺。

**Constraints**: 管理端仅站点主人可写；普通访客登录且账号有效后才可导出获准下载的已发布文章，匿名仍可阅读公开内容。图片须归档为站点受控资源；`asset://` 解析与媒体读取必须按当前可见版本和资源用途授权，不能仅凭 ID 放行。撤稿和撤销下载许可后直接访问必须失败；业务校验不得依赖数据库异常，外键不使用级联删除；首版不实现 Agent 与多级缓存。

**Scale/Scope**: 单一站点主人、少量项目与技术博客、公开匿名阅读。项目仅有卡片和外部入口；文章有独立阅读页。先完成可本地验收的完整纵向流程，再考虑部署与容量优化。

## Constitution Check

*GATE: Phase 0 前及 Phase 1 后均检查。*

`.specify/memory/constitution.md` 目前仍是未填写、未批准的模板，没有可执行的项目原则，因此不存在来自该文件的否决项。以本轮用户明确要求作为本次规划门槛：

| 门槛 | Phase 0 | Phase 1 复核 |
| --- | --- | --- |
| 前后端分离且公开内容可服务端呈现 | 通过：Nuxt + Spring Boot 分进程 | 通过：公开 HTTP 合同与 SSR 验收已定义 |
| PostgreSQL 替换 MySQL 为唯一关系型主库 | 已确认并执行本地切库 | 已通过：真实库迁移、现有数据逐字段比对、32 项后端测试和公开接口比对；非空旧文章迁移仍待演练 |
| 本地及网络图片统一归档 OSS | 通过：本地模式仅供开发 | 通过：媒体合同要求 OSS 验证及公开前完成归档 |
| SEO/GEO 有可验证的内容与技术基础 | 通过：不承诺排名 | 通过：原始 HTML、元数据、站点地图和结构化数据可验收 |
| Agent 与 Redis/Caffeine 不进入首版 | 通过 | 通过：保留 DocumentVersion 与显式发布指针；公开 Agent 检索授权在第二阶段测试 |
| 服务器、域名、正式部署后置 | 通过 | 通过：quickstart 只要求本地运行 |
| 后端 Swagger/OpenAPI 可本地核验且上线不暴露管理调试入口 | 通过 | 通过：文档路径、权限与验收已定义 |
| 服务端先完成全部业务校验；数据库可建 UNIQUE/FK | 通过：数据模型明确边界 | 通过：接口业务错误、并发与清理验收已定义 |
| 所有主键自增；状态/类别/角色统一 SMALLINT | 已写入 PostgreSQL 基线 | 已通过真实库结构测试；新增 Document 状态的 Service 枚举校验待业务实现 |

**结论**: PostgreSQL 的本地运行与现有数据切换已通过；非空旧文章迁移和 Document/Blog 业务验收尚未通过，按 T064–T073 继续。若未来正式制定 constitution，需重新检查本计划。

## Project Structure

### Documentation (this feature)

```text
specs/001-personal-portfolio/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── postgresql-document-transition.md
├── quickstart.md
├── contracts/
│   └── http-api.md
└── tasks.md                 # 历史任务与当前 PostgreSQL/Document 任务的审核索引
```

### Source Code (repository root；部分已实现，Document/Blog 待开发)

```text
frontend/
├── app/pages/               # SSR 公开页与 /admin 管理页
├── app/components/          # 从 A 版视觉原型提炼的组件
├── app/api/                 # 统一 HTTP 调用和响应类型
├── app/constants/           # 页面错误与空状态提示文案
├── app/composables/         # 确有复用需要时的页面逻辑
├── public/                   # 静态资源
└── tests/
backend/
├── src/main/java/.../
│   ├── controller/           # 只处理 HTTP 边界
│   ├── service/              # Service 接口
│   │   └── impl/             # 对应实现类，业务规则、权限、事务
│   ├── mapper/               # MyBatis-Plus 数据访问
│   ├── entity/               # 数据库实体，不直接暴露给 API
│   ├── dto/                  # 专用请求与响应类型
│   ├── enums/                # SMALLINT 数值码的业务语义
│   ├── config/               # 配置和拦截器
│   └── common/               # 统一响应与异常
├── src/main/resources/db/migration/   # 已停用的 MySQL 历史迁移
├── src/main/resources/db/postgresql/  # dev 已启用的 PostgreSQL 基线
└── src/test/
infra/                       # 首版不含 Docker 配置；生产部署文件后定
prototypes/                  # 已完成的视觉参考，不作为运行时依赖
samples/markdown-import/     # 虚构测试素材，不注入正式公开内容
```

**Structure Decision**: 前端与后端独立构建、独立运行，通过同源 `/api/v1` HTTP 合同交互。Nuxt 服务端负责页面呈现与必要的请求转发，不复制内容权限或图片抓取规则；Spring Boot 是业务判断的唯一来源。管理页属于同一个 Nuxt 前端，不另建第三个应用。

## Complexity Tracking

目前 constitution 仍是未填写模板，没有可执行原则；前后端两进程、PostgreSQL 与 OSS 是用户确认的方向。旧 `research.md` 的 MySQL/Article 结论属于历史材料，需在对应模块实施前同步修订。
