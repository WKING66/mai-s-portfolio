# Agent 执行约束

## 项目与职责

- `main` 用于稳定发布，`dev` 用于集成与联调。
- 后端 Agent 负责 `mai-portfolio-backend/` 的 Spring Boot、PostgreSQL、Redis、认证授权、Blog/Document/Asset、OSS、API/OpenAPI，以及 `mai-portfolio-ai-server/` 的 Python AI 服务。前端 Agent 负责 `mai-portfolio-frontend/` 的 Nuxt 页面、组件、交互、渲染和性能。
- 前后端各用自己的长期 Worktree 目录，任务分支按需切换。开始前检查工作区状态、分支和基线，保护已有改动、暂存、stash 与未合并成果。未经授权不得跨工作区或修改对方业务代码。跨端先确认契约，交接接口变化与待办。共同规范由后端 Agent 维护，前端规范由前端 Agent 维护。
- 当前优先博客与统一 Document 生命周期，RAG 和知识库属后续阶段。

## 开发前阅读

- [Git 协作与分级验证规范](docs/development/git-workflow.md) 负责分支准备、提交与交付、独立子 Agent 自审、PR 审核、发布及 V0–V3 验证。
- [后端编码规范](docs/development/coding-style.md) 负责后端代码组织、Java 排版、中文业务注释和持久化。修改后端前完整阅读，交付前对照本次差异。
- [前端编码规范](docs/development/frontend-coding-style.md) 负责 Nuxt 分层、编辑会话、交互性能和测试。修改前端前完整阅读，交付前对照本次差异。

## 授权与质量

- 已授权实现、必要局部修复、分级验证以及任务提交与 PR 可自主执行。重大架构变化、破坏性数据库或 API 变更、认证权限语义变化、跨职责业务修改，以及合并、强推、重写公共历史、删除长期分支和改动 GitHub 保护或审批规则，须先获得维护者明确批准。维护者负责合并，Agent 不启用或执行自动合并。
- 以确认的业务规则为准，核对事实来源、职责边界和失败路径。复用已有能力，不为测试硬编码，不为假设性扩展增加抽象。真实验证与测试替身的结果分别如实报告。
- 同一问题连续两次修复失败且无新证据时，停止沿原假设试改。列清预期、事实与假设，执行最小区分性验证，仍无法定位时汇报证据与候选根因。
- 本项目禁止使用 `ponytail` 技能。
