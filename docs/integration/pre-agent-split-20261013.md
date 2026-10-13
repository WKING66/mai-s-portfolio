# 双 Agent 拆分前的本地集成候选 — 2026-10-13

## 结论与发布状态

现有前后端成果已在独立集成工作树保留历史集成，候选测试、构建及基础真实 HTTP/页面检查完成。**尚未建立新的 main 基线，暂不推送、不创建 PR、不合并 main，也未恢复后端新功能开发。**

原因是候选历史存在硬编码 OSS 凭据。维护者明确选择“先完成验证和报告，暂不清理历史或推送”。当前文件已移除凭据默认值，历史仍保留；不能将当前文件清理等同于历史清理或安全发布。本文不记录凭据值、私钥、Cookie、正文或签名 URL。

## 1. 起点、输入和工作区

| 项目 | Commit / 路径 |
|---|---|
| main 原始 Commit | `0e89031e831aeb77567d0acb62a20b91ca1ac3bf` |
| origin/main 实时确认 | 与上述 main 一致；只读远程查询后执行 fetch，临时绕过不可用代理，无永久配置修改 |
| 前端已完成产品代码 | `4544a5d1fd48296c31c53835ed591bbed3934da1` |
| 前端完整交接检查点 | `10c2ad20c22ea203c5842bf7db463862d7448ee1` |
| 前端交接分支 | `feature/frontend-baseline-handoff-20261013` |
| 集成分支 | `integration/pre-agent-split`，从 main 建立 |
| 独立集成路径 | `F:/code/ai/codex/projects/mai-portfolio-integration` |
| 共享前端路径 | `F:/code/ai/codex/projects/mai-portfolio`；文件与分支均未由本 Agent 修改 |
| 集成最终 Commit | 本报告所在提交；以 `git rev-parse integration/pre-agent-split` 和最终会话汇报为准 |

完整阅读了 [前端交接报告](../handoffs/frontend-baseline-20261013.md)。交接文件中的建议属于审计输入，业务规则仍以维护者要求为准：下架阻止公开列表、文章详情和公开导出，不要求已签发静态资源 URL 立即失效。

## 2. 提交范围、祖先关系和冲突

从 `main@0e89031` 建立独立 Worktree 后，用 `merge --ff-only 10c2ad2` 纳入检查点。输入共 64 个提交：63 个开发提交，加一个交接提交。没有 Cherry-pick，没有 squash，没有重写历史，没有冲突覆盖。

全部本地及已知远程命名开发分支的提交均在输入检查点祖先链内；没有发现需要另合的独立后端分支。63 个开发提交中，53 个触及 frontend，16 个同时触及 frontend/backend；不能把当前前端分支误当成纯前端成果。

输入提交范围为 `0e89031..10c2ad2`，清单见 [源提交列表](source-commits-20261013.tsv)。逐提交前后端分类见前端交接的 [分类清单](../handoffs/frontend-baseline-commits-20261013.tsv)。本轮修复和报告在该检查点之后独立提交。

关键保留成果：

- 项目管理、网站资料、账号注册/登录/个人中心及密码/权限边界。
- API 职责收敛、统一 Asset 文件能力、私有 OSS 签名 URL 和静态资源直连。
- Blog/Document 不可变版本、资源准备、保存与发布分离、受保护 MD/ZIP 导出。
- 前端持久化账号快照、首屏定位、顶部 Toast、全站视觉、导入模式隔离和 Slug 编辑。
- `ac98628` 的公开 Markdown 契约及原文保真；相关前端 Comark/Shiki 阅读器一起保留。公开 BlogVo 使用已发布 `content`，不恢复旧 `html` 字段。
- 长文目录、折叠、公式、懒加载 Mermaid 和最终直接触摸手势。保留撤回尺寸手柄的后续提交，没有只合早期尺寸版本。

Git 文本冲突为零，**验证差异不为零**；下面列出实际发现和处理，不能用“快进无冲突”代替一致性验收。

## 3. 本轮修复及业务决策

### 3.1 账号删除时撤销旧会话

隔离数据库认证回归发现：旧测试期待删除账号后会话失效，但实际实现持续返回 403。维护者明确选择“账号删除时自动注销旧会话，返回匿名状态”。

现有 AccountAuthenticationService 增加可选当前账号查询，替代只检查令牌存在的内部 `isLoggedIn` 方法。会话展示、业务访问和加锁访问共用账号/凭据校验，删除判断与令牌撤销只在认证层实现：

- 账号删除：撤销当前旧令牌；会话查询返回 `200 loggedIn=false`；受保护业务请求返回 401。
- 账号禁用：仍为 403，允许显式注销。
- OWNER 撤销：仍是正常登录账号，管理能力拒绝，不错误注销。
- 凭据版本不匹配：沿用现有 401 规则；存储故障不包装成正常匿名成功。

补充三个使用真实 Sa-Token 与独立内存 DAO 的单元回归，并更新真实 PostgreSQL/MockMvc 删除账号契约。没有把职责塞入 Controller 或全局异常 Handler。

已删除的 challenge 路径当前返回 404，旧测试期待 401；将断言修正为已删除路由的契约，没有恢复旧挑战 API。

### 3.2 数据库说明和过时 Schema 断言

全新临时库实际迁移 V1–V5 后发现：主键数的旧断言忽略 V2 user_profile；外键数与 V1/V4 声明不符；V3 credential_version 缺少列说明。

- 按现有迁移声明校正为 16 个 id 主键和 21 个 RESTRICT 外键；不修改数据库关系来迎合测试。
- 新增 `V6__credential_version_description.sql`，仅增加数据库列说明，不改凭据值、字段结构或现有 V1–V5 文件/校验和。
- 在临时库执行实际 Flyway 升级及 Schema 契约回归。所有业务列说明检查通过。

### 3.3 配置与验收脚本

`fe334d8` 引入的 application-dev.yaml 在环境表达式内包含 OSS AccessKey 默认值。当前集成文件改为必须由环境注入，保留现有 OSS 能力；未修改 Bucket 权限或凭据。

新增 [隔离 HTTP 验证脚本](../../scripts/integration/verify-baseline.mjs)，只允许显式确认的 loopback 3040 环境，使用临时测试账号/数据库，无 mock 响应。只输出接口结果，不输出凭据、正文或签名地址。既有 Mermaid 浏览器脚本增加 `EXTERNAL_ACCEPTANCE_URL`，默认 3000 行为不变，允许集成在 3040 复验。

## 4. 未提交内容和旧 stash 的保留

共享目录中的四个修改保持原样，不纳入集成：AssetQueryServiceImpl 注释、ProjectController FIXME、ProjectServiceImpl FIXME、流程图注释拆行。它们不改变可执行行为，归属仍待维护者确认。交接前后的共享分支/HEAD 和修改范围未由本 Agent 改动。

旧 stash `aa57866` 及 indexed parent `8a8c670` 保留，没有 pop/apply/drop。23 文件补丁含已被替代的 Admin/Registration/字节代理路径、常量移动及全局校验 400 补丁。后续已提交核心实现包含统一注册、领域常量和独立凭据异常边界，不能直接恢复旧 stash 并重引入这些职责问题。

本轮没有声称逐字吸收该 stash 的全部注释/WIP；它不作为新的集成输入。维护者如需保留特定意图，应点名独立处理。所有命名分支的已提交业务成果都已保留，stash 和四处未提交工作仍能恢复。

## 5. 验证环境与结果

前端依赖从共享目录复制成独立副本，没有 Junction、没有修改共享 node_modules/.nuxt/.output，没有在全新机器重跑 npm ci；依赖全新安装复现仍未验证。

临时 PostgreSQL 单独初始化在集成忽略目录，监听 loopback 55441。portfolio_dev、blog_document_test_integration_20261013 和 baseline_candidate_20261013 均属于该临时实例，不是维护者 5432 开发库。临时 RSA 密钥和账号仅用于验收。候选后端监听 9340，候选生产前端监听 3040，现有 3000/9333 服务保持运行。

| 检查 | 本轮实际结果 | 边界 |
|---|---|---|
| 首轮普通后端测试 | 244 总计，196 执行通过，48 条件跳过 | 没有把外部环境跳过算通过 |
| 修复后完整后端测试与 package | 247 总计，240 执行通过，7 条件跳过，0 失败/错误 | 包含 Schema、认证/权限、API/OpenAPI、架构及真实 PostgreSQL Document 生命周期 |
| 临时数据库迁移 | 新鲜库 V1–V6 均成功，已有测试库 V5→V6 成功 | 没有对维护者数据库执行迁移/清空 |
| Nuxt Vitest | 31 文件，287 项通过 | 新工作树先 Nuxt prepare；首次缺生成 tsconfig 的运行未执行断言，准备后重跑通过 |
| Nuxt typecheck | 通过 | 集成副本 |
| Nuxt production build | 默认构建及代理至 9340 的隔离构建均通过 | 既有 DEP0155 警告保留 |
| Lint | 未执行 | 仓库没有 lint 脚本、配置或依赖；未伪造通过或临时引入规则 |
| 真实候选 HTTP | 21 项通过，另有 5 项下架/注销检查通过 | 3040 代理→9340→临时 PostgreSQL；实际登录、注册、角色拒绝、保存/发布、409、草稿隔离及正文下载；无 mock |
| 真实页面/API 只读 | 5 项 API、37 项页面/交互通过，errors=[] | 1280/390，浅深主题，公开栏目、匿名路由、代码主题、目录/折叠、SSR 刷新；合成文章 |
| 管理生命周期浏览器 | 2 项通过 | 候选真实前端；隔离浏览器 API fixture，不是云端端到端 |
| 导入浏览器检查 | 通过 | 240/280/320/390 面板、重选、异步隔离、Slug、资源选择；API fixture |
| Mermaid 浏览器检查 | Edge 4 组通过，errors=[] | 1280/390、浅深主题；正文/全屏拖动缩放、源码、主题；不等同于真机验收 |

首轮启用数据库的运行曾有 5 个失败（AuthSecurityTest 2、SchemaContractTest 3）。分别根据维护者确认的会话业务规则、删除路由和实际声明迁移处理后完整复跑，未忽略失败或弱化权限。

7 个跳过项是 RedissonIntegrationTest 2、NamespacedSaTokenDaoIntegrationTest 2、AliyunOssObjectStorageIntegrationTest 1、RedisAuthFlowTest 1、RegistrationRedisFlowTest 1。候选 HTTP 实例实际使用独立 Redis 会话命名空间，但这不替代这七项专项兼容/云资源测试。

**没有执行**真实 OSS 上传、签名到期回收、云资源 ZIP 整条链路、最新手势真机和完整性能 trace。Document 资源/ZIP 流程的数据库测试使用模拟存储，HTTP 真下载本轮为无资源 MD。此前云端验收不混写成本轮结果。

## 6. 复验入口

前端独立目录执行：

```text
node node_modules/nuxt/bin/nuxt.mjs prepare
node node_modules/vitest/vitest.mjs run
node node_modules/nuxt/bin/nuxt.mjs typecheck
node node_modules/nuxt/bin/nuxt.mjs build
```

浏览器脚本需要已有 Playwright、Edge 及 `EXTERNAL_ACCEPTANCE_URL=http://127.0.0.1:3040`。Mermaid 使用 `MERMAID_VIEW_CAPTURE=0` 避免新增工件；导入和基线结果写入忽略目录。

后端用 JDK 21、现有 Maven 缓存，在 backend 执行 `mvn -o -pl mai-portfolio-launch -am package`。启用数据库测试必须将 PSQL_PORT 设置为独立实例端口、BLOG_TEST_JDBC_URL 设置为命名符合安全约束的独立库，设置临时 OWNER_PASSWORD，不直接沿用开发库环境。JVM 临时目录放在集成忽略目录。

真实 HTTP 验收执行 `node scripts/integration/verify-baseline.mjs`，要求 BASELINE_ISOLATED=1、BASELINE_OWNER_PASSWORD、BASELINE_PUBLIC_KEY_FILE、BASELINE_OUTPUT_DIR。验收脚本以受限 3040 目标为边界，但隔离数据库的创建/配置仍须运行者确认。首次运行保留合成已发布文章供页面脚本检查，随后使用 `--cleanup` 下架并注销。测试服务器停止后需重新准备环境，不能对维护者现有服务直接执行该脚本。

原始日志、API 摘要、浏览器结果、临时数据库、临时密钥和构建产物均在 `.local-backups/integration-20261013` 或忽略的生成目录，不提交。

## 7. 维护性与未实施扩展

账号不存在、账号禁用、凭据版本和角色差异有各自业务语义，共用认证服务校验，不靠全局异常特殊分支。Document/Blog 的事实来源及统一 Asset 能力保持，未新增页面/角色上传服务或跨模块 Mapper 引用。

本轮没有提前做 RAG、Chunk、Embedding、MQ、DOCX/PDF Parser、正式 UEditor 替换或额外注册框架。前后端通过现有 OpenAPI/Markdown/资源引用契约协作；后端修复没有替换前端阅读器。

已知债务：缺少统一 lint/e2e CI、依赖未全新安装、云存储/真机/大文件并发未复验、旧 stash 尚有 WIP 意图待确认、约 606 MiB 历史验收工件较重。没有为精简历史删证据或重写功能。

## 8. main 更新方案与角色切换门槛

当前候选保留 `0e89031..10c2ad2` 全部输入历史，并追加本轮修复/验证提交。技术上 main 是其祖先，可快进；**当前不推荐把含凭据历史的候选发布到远程或更新 main**。

后续顺序：

1. 维护者决定对应凭据处置和未发布历史清理方案；独立执行、保留原始分支，不强推或重写 main。
2. 清理后核对新旧功能树，仅允许已审核的秘密/修复差异，重新核对提交映射及 main 祖先关系。
3. 在获得推送授权后发布清理后的集成分支、创建 PR，再交维护者最终审核。原本本轮有推送/PR 授权，但维护者后续明确要求暂不推送，后者生效。
4. 获得明确批准后，推荐快进更新 main 以保留完整功能提交历史；如远程 main 前进导致分叉，先重新集成，不强制覆盖。
5. 用户确认新 main 基线实际建立后，再从一致的最新 main 创建后端专用 Worktree 和 `agent/backend-<task>` 分支。本轮不创建该未来工作树，不继续旧全栈功能任务。

主工作目录、main、origin/main、其他历史工作树和 stash 均保持。本候选是可审阅的本地集成结果，发布阻塞和未验证事项明确保留，不能标记为新的 main 基线已经建立。
