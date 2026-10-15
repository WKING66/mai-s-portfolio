# Agent 执行约束

完整共同规范见 [Git 协作与分级验证规范](docs/development/git-workflow.md)，它是工程流程的唯一完整来源。共同规范由后端 Agent 维护，前端 Agent 不同时编辑。

仓库源码子目录为 mai-portfolio-backend/、mai-portfolio-frontend/；Git 分支仍为 backend、frontend。源码子目录与外层 Worktree 不是同一概念，工作区位置以 git worktree list 为准。

## Git、工作区与验证

1. main 用于稳定发布，dev 用于集成，backend/frontend 是各 Agent 长期开发分支。日常 PR 指向 dev，发布 PR 为 dev → main；本规则替代普通任务直接向 main 提 PR 的旧规定。
2. 使用各自独立 Worktree；任务前检查状态、分支与基线，保留 WIP、stash 和未合并成果。不修改另一 Agent 工作区，不覆盖未知修改，不重复创建工作区。
3. 长期分支 PR 统一 Merge Commit，禁止 squash/rebase；未经明确授权不直接向 dev/main 提交或推送功能，不自动合并、强推/重写公共历史或更改保护规则。
4. PR 合并后获取最新 dev，在自己的干净工作区快进同步并推送长期分支；无法快进先核对未合并成果，不强制覆盖。backend/frontend 合并后不删除；确需并行时使用短期任务分支，完成后清理。
5. Conventional Commits 使用准确 scope，精确暂存任务文件并检查 Diff；不无检查地 git add .，不混入无关格式化、重构、凭据、本地配置和测试产物。
6. 按 V0–V3 风险执行最小充分验证。V0 仅检查 Diff、内容/格式，不运行应用测试或构建；不默认全量测试，不重复已通过且未受影响的检查，不把 Mock 称为真实联调。
7. 完成后提交、推送并创建 dev PR，简述功能、范围、验证等级/结果、接口变化和风险。发布阶段固定 dev 候选并集中验收，维护者最终批准后合并 main。
8. 对外资料放 resources，私人材料放 private；本文件与维护者指定的共同开发规范纳入 Git，其他内部材料仍不提交。联调/Windows/辅助测试工具不提交，用完清理；正式功能完成后删除演示组件与演示 URL。

## 开发前同步 dev（强制）

每次新任务开始前，在自己的 Worktree 检查当前分支和未提交修改；没有需要保护的进度后，必须执行 git fetch origin、git merge --ff-only origin/dev、git push origin <当前开发分支>。同步成功才能开始，纯同步属于 V0，不运行 Maven、Nuxt 或端到端测试。

- 有正在进行的任务、未合并 PR 或未知 WIP，先保护进度和核对任务状态，不为同步丢弃成果。
- 快进失败先检查未合并提交、未完成任务或分叉，选择保留成果的合并/协调方案；禁止强制重置、强推或擅自重写历史。
- 实现后最小充分验证并创建 frontend/backend → dev PR；Merge Commit 合并，禁止 squash。只有确认本轮提交进入 dev 后才开始下一轮同步，不自行合并 dev/main。
- dev 是已集成代码的共同来源。跨端功能先按正常 PR/审核流程确认契约，双方再分别开发；另一 Agent 未合并的代码不能视为已在 dev。依赖通过明确接口交接或提前合入可独立验收的契约变更解决。

## 后端编码规范

遵循 [后端编码规范](docs/development/coding-style.md)。所有自有 Java 与 POM 纳入统一格式检查，分批整改不得用排除路径或关闭规则掩盖存量问题。

- Service/Mapper 依赖和局部引用使用明确的 xxxService/xxxMapper 名称；框架对象体现真实类型与职责。重命名核对注入、Qualifier/Resource、反射与测试引用。
- 关键业务方法按真实流程写中文步骤注释，解释事务、锁、外部资源操作、失败和已有补偿约束；简单委托不堆砌注释。
- 控制流程使用大括号并换行，禁止压缩式方法和多语句代码块；复杂表达式与 POM 结构清晰展开。
- 所有生产数据类（含 DTO/VO/DO、配置对象与嵌套 record）注解和字段分行，每个注解单独一行，字段之间留一个空行；字段顺序与业务语义不变。格式化器输出不得覆盖此标准，详见编码规范。
- 纯格式/注释仅执行格式、Diff 和必要 XML 检查；重命名/组织调整执行关联编译与测试，关键业务代码增加定向回归。
- 不借整改改变 API、Schema/Flyway、鉴权、Document 版本、事务/补偿、OSS 或 Bean 装配契约。按完整模块分批 backend → dev，前一批合并并同步 dev 后再继续。

## 职责与质量

- 后端 Agent 负责 Spring Boot、数据库、Redis、认证/权限、Blog/Document/Asset、OSS 和 API/OpenAPI；未经授权不修改 Nuxt 业务代码。跨端变化先明确契约，提供简短交接。
- 已授权实现、必要局部修复、分级验证和 Commit/PR 自主执行；重要架构、破坏性数据库/API、认证/权限语义变化等须先获明确批准，详见完整规范。
- 生产代码服务于真实业务与维护性；实现前识别稳定规则、已明确变化点、职责层和可复用逻辑，不为测试硬编码或为假设性未来需求堆砌抽象。
- 完成后检查事实来源、重复规则、职责边界及失败路径；测试通过不代表任务完成，未验证内容如实报告。
- 当前优先博客与统一 Document 生命周期，RAG/知识库是第二阶段，不扩展无当前调用价值的能力。

本项目禁止使用 ponytail 技能。

## 业务模块目录规则

1. 同一业务模块内只维护一套 Controller、Service 接口/Impl、Mapper 三层架构，配置、实体、枚举、常量及功能组件放在对应职责包中。
2. 禁止在模块内部以子业务包再嵌套一套三层架构，例如 system/tag/service、system/tag/mapper；并入 System 的标签类直接放入 system/service、system/mapper 等现有目录。
3. 需要独立业务边界时，按同级 Maven 业务模块拆分，不通过业务包的父子嵌套替代模块边界。
