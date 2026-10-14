# 阿霾的个人作品集

个人网站与内容管理项目，包含公开资料、项目作品、账号管理与技术博客。正式内容由站长维护，不自动填入演示项目或测试笔记。

## 当前能力

- 网站资料、项目和标签展示与管理。
- 注册、登录、个人资料、头像与密码管理；账号共用认证体系，站长操作由 OWNER 权限控制。
- 博客 Markdown 导入、Document 草稿版本、显式发布、公开阅读及 Markdown/ZIP 导出。
- 统一 Asset 图片与附件上传；私有 OSS 签名 URL 由浏览器直接访问。
- Markdown 代码高亮、公式、Mermaid、目录与折叠阅读。

当前优先完成博客文档生命周期，RAG 与知识库属于第二阶段。

## 技术与目录

Nuxt 4、Nuxt UI、Tailwind CSS 4；Java 21、Spring Boot 4、Maven 模块化单体；PostgreSQL/Flyway、Redis/Redisson、Sa-Token 与 OSS。

| 目录 | 用途 |
| --- | --- |
| `mai-portfolio-frontend/` | 正式页面、组件、渲染及功能单元测试 |
| `mai-portfolio-backend/` | 后端业务、基础设施、数据库迁移及功能测试 |
| [resources/](resources/README.md) | 项目启动、使用、数据库和必要设计资料，随 Git 发布 |
| `private/` | 维护者审核、Agent 内部材料，不随 Git 发布 |

本地联调、Windows 辅助脚本和演示组件不属于正式交付，使用完后清理。完成正式功能后删除演示路由，不保留开发环境访问开关。凭据、日志和构建产物不进入 Git。
