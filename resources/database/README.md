# 数据库初始化与迁移

后端使用 PostgreSQL 与 Flyway。启动前创建空的专用开发库 `portfolio_dev`，向启动进程注入连接配置，再由 Flyway 按顺序执行迁移。不要在维护者已有业务库中运行破坏性初始化脚本，也不要重复手工执行已有迁移。

迁移的唯一源文件在 [后端运行时迁移目录](../../mai-portfolio-backend/mai-portfolio-launch/src/main/resources/db/postgresql/)。这些 SQL 随后端应用打包，不能作为普通文档移出 classpath；此处只提供入口，不维护另一份 SQL。

| 迁移 | 内容 |
| --- | --- |
| [V1](../../mai-portfolio-backend/mai-portfolio-launch/src/main/resources/db/postgresql/V1__portfolio_and_document_core.sql) | 作品集与 Document 核心结构 |
| [V2](../../mai-portfolio-backend/mai-portfolio-launch/src/main/resources/db/postgresql/V2__account_profile.sql) | 账号资料 |
| [V3](../../mai-portfolio-backend/mai-portfolio-launch/src/main/resources/db/postgresql/V3__account_credential_version.sql) | 凭据版本 |
| [V4](../../mai-portfolio-backend/mai-portfolio-launch/src/main/resources/db/postgresql/V4__asset_usage_and_uploader.sql) | Asset 用途与上传者 |
| [V5](../../mai-portfolio-backend/mai-portfolio-launch/src/main/resources/db/postgresql/V5__document_resources.sql) | 文档资源 |
| [V6](../../mai-portfolio-backend/mai-portfolio-launch/src/main/resources/db/postgresql/V6__credential_version_description.sql) | 凭据版本说明 |

已执行的迁移不改写；后续 Schema 变化通过新增迁移处理。运行配置和启动入口见 [快速开始](../quickstart.md)。
