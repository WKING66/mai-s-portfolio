# 阿霾的个人作品集

个人网站与内容管理项目，围绕公开资料、项目作品和技术博客建设。项目正在实施中，正式内容由站长维护，不自动填入演示项目或测试笔记。

## 当前功能

- 网站资料与项目展示：公开资料、项目列表、标签及站长管理。
- 账号与权限：注册、登录、个人资料、头像与密码管理；普通用户和站长共用账号体系，管理能力由权限控制。
- 博客与 Document：Markdown 导入、草稿版本、显式发布、公开阅读，以及登录后的 Markdown/ZIP 导出。
- 统一资源管理：图片、头像和附件共用 Asset 上传能力；私有 OSS 生成签名 URL，浏览器直接加载资源。
- Markdown 阅读：代码高亮、公式、Mermaid、文章目录与折叠交互。

当前优先完成博客文档生命周期。RAG、知识库与 Agent 检索属于第二阶段，尚未实现。

## 技术与结构

前端使用 Nuxt 4、Tailwind CSS 4 与 Nuxt UI；后端为 Spring Boot 4 的 Maven 模块化单体，运行于 Java 21，使用 PostgreSQL、Flyway、Redis/Redisson、Sa-Token 和阿里云 OSS。

| 目录 | 职责 |
| --- | --- |
| `frontend/` | 页面、组件、交互和 Markdown 渲染 |
| `backend/mai-portfolio-modules/` | System、Portfolio、Asset、Taxonomy、Blog、Agent 业务模块 |
| `backend/mai-portfolio-framework/` | Web、认证、持久化、对象存储等基础设施 Starter |
| `backend/mai-portfolio-common/` | 已确认的跨业务公共能力与模型 |
| `backend/mai-portfolio-launch/` | 应用启动、配置与数据库迁移 |
| `docs/` | 必要架构设计、业务流程与开发规范 |

业务模块遵循 Controller → Service → Mapper 分层，跨模块通过 provider 协作。Blog/Document 保存正文与版本事实，Asset 保存文件位置；普通资源由 OSS 传输，受保护的动态导出由后端处理。

## 文档

- [快速开始](quickstart.md)：本地运行、配置、接口使用与验证。
- [文档导入导出链路与流程图](docs/architecture/document-import-export.md)。
- [认证与授权设计](docs/architecture/authentication.md)。
- [Git 开发规范](docs/development/git-workflow.md)。

## 仓库收录范围

收录功能代码、功能单元测试、必要项目文档与配置模板。本地集成/浏览器验收脚本、测试工具、测试数据及验收、审计、性能报告仅保存在被忽略的本地目录，不进入开发提交。运行配置、凭据和构建产物同样不入库。
