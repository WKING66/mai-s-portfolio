# Git 开发规范

main 是唯一稳定公共基线和默认分支。正常任务采用 `任务分支 → PR → main`，不建立长期集成父分支。

## 目录与任务

- 前端、后端分别使用独立 Worktree；开始任务先检查本目录与公共仓库状态，保留已有修改。
- 从已同步的 main 创建 `feature/frontend-<task>` 或 `feature/backend-<task>`，一项任务对应一个分支、一个 PR；记录父分支和基线 SHA。
- 后端负责 Spring Boot、数据库、认证、Document/Blog/Asset 及正式 OpenAPI；前端负责 Nuxt、交互、渲染和性能。跨端变化先明确接口契约，不擅自改另一端产品代码。
- 验收默认前端 3000、后端 9333。端口不决定数据库归属：写入测试仍必须使用隔离数据库、独立 Redis 前缀和明确授权的 OSS 对象。已有服务占用时先核对，不强行结束进程。
- 大功能确需多任务集成时才用临时父分支，进入 main 后删除；不能以父分支为由绕过 main 审核。

```text
git fetch --prune origin
git worktree add -b feature/backend-<task> ../mai-portfolio-backend origin/main
# 前端使用 feature/frontend-<task> 和自己的工作区；已有目录先核对复用，不覆盖。
```

前端当前仍在原共享目录，后端已隔离。原目录四处 WIP 和旧 stash 在归属确认前保留；前端迁移由其 Agent 完成检查点后执行，不能代为切换正在使用的目录。

## 提交、PR 与合并

- Conventional Commits：`fix(auth): ...`、`feat(blog): ...`、`docs(workflow): ...`；scope 对应实际模块。
- 精确暂存本任务文件，检查 diff，运行受影响测试/必要构建后提交；不带 `.env`、凭据、日志、构建产物、数据库或其他 Agent 的 WIP。
- PR 说明问题与结果、接口/Schema 变化、风险级别、验证版本与真实结果、未覆盖范围；普通任务不额外制造交接文档。
- 自检包含职责、重复规则、事实来源、失败路径、配置/日志/异常与已有扩展点；测试通过不代替维护性审核。
- Agent 可自行准备 PR，独立 Review Agent 可辅助；未经维护者明确授权，任何 Agent 不得自动合并 main。
- CI 不替代设计审核。不修改分支保护或审批权限来绕过审核；自动合并规则必须单独获授权。
- 合并后先确认远程 main 与预期代码一致，再删除任务的本地/远程分支及无 WIP 的工作树。快照替代须核对实际文件成果后才允许删除非祖先分支引用。
- Worktree 删除前核对绝对路径、未提交/忽略文件及使用者；stash 单独审核。分支删除不等于清除 Reflog、旧对象或备份，不进行破坏性历史清理。

## 风险与验证

| 级别 | 范围 | 额外要求 |
| --- | --- | --- |
| L0 | 文案、样式 | 页面自检、受影响端基础检查，仍通过 PR 审核 |
| L1 | 普通功能、局部修复 | 相关测试、必要构建及主要真实调用；权限不由前端展示状态决定 |
| L2 | Schema、认证、权限、破坏性接口、重要架构 | 维护者审核设计；隔离迁移/升级检查、拒绝路径及跨端契约验收 |

## CI 建议

[ci-pr.example.yml](ci-pr.example.yml) 是待审核模板，未安装到 `.github/workflows`，没有调整仓库审批策略。

- 快速 PR：Java 21/Maven 构建及现有测试（临时 PostgreSQL/Redis）；Node 24 `npm ci`、Nuxt prepare/typecheck、Vitest、生产构建；`git diff --check`；Gitleaks 对 PR 提交脱敏扫描。
- 实际测试必须输出通过/失败/条件跳过数；现有数据库 Document 测试模拟对象存储，不能称为云资源端到端。OSS 专项在没有授权凭据的 PR 环境中不启用，发布前单独验收。
- 当前无 ESLint 配置/命令，不虚构 lint 通过；由前端 Agent 提出规则后再接入。已有 Maven 架构/契约测试和 Nuxt typecheck 作为当前静态检查。
- 发布前/高风险变化：默认端口真实 HTTP/浏览器验收、V1–V6 新库与升级、注册登录/会话失效/OWNER、草稿与公开版本隔离、MD/ZIP、授权 OSS 上传/签名/图片/补偿、长文章/移动视口/SSR。
- 普通 PR 不运行全部耗时浏览器矩阵；发布验收可手动触发或定期执行，必须使用一次性环境。云凭据不注入未审核外部 PR。
- 任一 P0/P1 未解决则验收不通过；P2 登记后续任务。禁止改业务迎合错误测试、降低断言或静默跳过来换取绿灯。

模板版本和用法依据 [checkout](https://github.com/actions/checkout)、[setup-java](https://github.com/actions/setup-java)、[setup-node](https://github.com/actions/setup-node)、[Gitleaks](https://github.com/gitleaks/gitleaks) 官方说明；启用前验证 GitHub runner 和依赖冷安装，不将本机结果称为 CI 已通过。
