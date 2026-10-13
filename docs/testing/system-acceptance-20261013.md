# 系统验收与 Git 整理（2026-10-13）

## 版本与结论

业务验收基于 main/origin/main `fedbc9aaf6e0fbf025ef71e6c2044e8a92c29959`，其文件树与已合并快照 `998fba7` 一致。本轮分支 `backend/system-acceptance`，父分支 origin/main；未再次迁移基线，未修改 main。

已实施功能的核心 HTTP、OSS 和浏览器链路通过，未发现业务 P0/P1。**main 的后端启动入口仍需合并本轮修复后关闭验收阻断项**：修复只在任务分支，不能称为已发布到 main。CI 模板亦未启用。

## 环境与实际结果

按维护者要求使用前端 **3000**、后端 **9333**。临时 PostgreSQL 在 loopback 55442，数据库 `system_acceptance_20261013`；使用独立 Redis 前缀 `acceptance-20261013-55442`。没有写入维护者 5432 开发库。最初 3040/9340 的结果仅作为诊断，正式结果均已在默认端口复验。

OSS 使用维护者授权的现有私有 Bucket 和已轮换配置；只创建随机对象、按明确清单回收，不调整 ACL。临时账号、RSA 密钥、下载文件和日志均被 Git 忽略，不提交。

| 验收 | 实际结果与覆盖 |
| --- | --- |
| 核心 HTTP 冒烟 | 21 项通过；真实 Nuxt 代理、注册/登录、角色拒绝、新建/保存/发布、CAS 409、公开版本与草稿隔离、无资源时选择包含资源仍返回 MD |
| 完整系统检查 | 43 项通过，completed=true；真实 HTTP/数据库/Redis/OSS/Edge，无接口 fixture；另检查有效签名读取、无签名 403、头像上传绑定、图片/附件、版本资源稳定引用、MD URL 与 ZIP 本地资源内容 |
| 账号与拒绝路径 | 昵称读写、CSRF 拒绝、旧密码错误 422、修改密码后旧凭据 401、禁用账号 403 且可注销、OWNER 撤权 403、删除账号后 session 匿名/受保护 API 401；账号状态变更只在隔离库执行，没有虚构管理账号 API |
| 真实管理浏览器 | 注册、登录、编辑/保存/发布、实际下载 MD/ZIP、个人资料保存刷新、图片/公式/Mermaid/SSR；额外从管理列表新建文章、真实选择图片附件上传、保存发布、公开图片加载、下架注销均通过 |
| 下架与资源 | 公开详情 404、登录后的公开导出 403；下架后此前签发的图片链接仍可读，符合现行规则 |
| 真实导入与补偿 | Markdown 携带本地图片及附件导入成功；隔离库临时约束注入文档持久化失败，409 返回、文档/资源数据库回滚、实际上传 OSS 对象由业务补偿删除，SDK确认不存在；临时约束已移除 |
| 公开阅读浏览器 | 5 项 API + 37 项页面/交互通过，errors=[]；120 章节合成文章、1280/390 视口、浅深主题、列表跳转、代码主题、目录/折叠、刷新/SSR、匿名管理拦截 |
| 构建与入口 | 最新 main 后端 package、Nuxt production build 通过；默认端口代理重新构建通过；PowerShell 三个入口语法解析通过，新 -EnvFile 入口从仓库根目录执行真实 OSS 专项 1 项通过 |

这是自动化真实浏览器联调，不是维护者手工真机验收。没有把 Mock 的数据库测试或 API fixture 计入以上真实结果。

既有验证只作参考：`a43d5d9` 后端 240 执行通过/7 外部条件跳过、Nuxt 287 通过及 typecheck/build；本轮没有重复全量旧测试。前端产品代码与该已验证代码仅有已合并的空白差异。没有现成 lint 命令；没有声称 lint 或远程 CI 已通过。

## 修复与设计审查

`run-local.ps1`、`test-redis.ps1`、`test-oss.ps1` 原来固定读取根目录 .env，且 Maven 路径依赖调用目录。现在默认 `backend/.env`，支持 `-EnvFile`，所有 POM 用脚本目录绝对定位；同步 README 和配置注释。未将凭据写回 YAML，也未修改另一 Agent 的 Nuxt 产品代码。

本轮不改业务规则、API、Schema、认证实现或 Document/Asset 分层。配置文件是启动输入，环境变量是应用读取边界；既有统一文件/文档能力继续是事实来源。测试端复用 OSS SDK做精确清理，没有新增生产上传服务或未来抽象。新增资源类型不需要复制上传 Controller/Service。

不提前设计 RAG、MQ、额外文档格式或上传框架。启动脚本保留既有三类操作入口；未为本次路径修复引入新的配置框架。

## 未覆盖与后续事项

- 签名有效及过期时间投影已检查，未等待默认 1 小时验证自然到期；网络曾超时，复验成功后先前 10 个对象及后续每轮对象均精确清理。
- 未做物理设备、Safari/WebKit本轮复验、极大文章/文件、并发性能或完整 trace；120 章节视口交互不等于性能 SLA。
- 网络图片远程抓取的真实外站链路、前端目录选择导入全矩阵尚未在本轮实测；本地图片/附件导入与统一文件上传已实测。DOCX/PDF尚未实现，不冒充已支持。
- CI 配置是建议，尚未在 GitHub runner 验证；全新依赖冷安装未复验，Nuxt依赖为独立副本。
- 同账号再次登录会更新当前会话 CSRF；验收客户端主动读取当前快照后继续写入，未擅自改变现行会话策略。

## Git 实际清理

main 与 origin/main 一致，远程默认分支及 origin/HEAD 指向 main。已删除 **42 本地、16 远程分支**和 **8 临时工作树**；普通删除用于 main 祖先，快照替代历史经集成成果核对后删除分支引用。未推送旧敏感历史，未强推或清理 Reflog/不可达对象。

清理包括 integration/pre-agent-split、feature/unified-baseline-20261013，以及旧认证、账户、项目、博客、Mermaid、反馈、视觉/展示任务分支。工作树为 integration、snapshot、blog-document、convergence、fix、design-showcase、ds-polish、visual；忽略目录仅为已核对的旧测试/构建产物，无实际 .env 或代码 WIP。

保留：main；backend/system-acceptance（本轮）；feature/frontend-baseline-handoff-20261013（前端 Agent 正在使用）；feature/acceptance-environment（f283e2a 的独有端口说明未集成）。前端原目录四处 WIP、旧 stash aa57866 均未修改；stash含已替代实现，但部分注释意图未确认，不直接恢复或丢弃。另已删除67个确认可再生成的旧测试/Nuxt输出目录；前端当前检查点、Playwright工具缓存、参考仓库及可能含源码意图的少量备份保留。

前端工作区 `F:/code/ai/codex/projects/mai-portfolio`，后端工作区 `F:/code/ai/codex/projects/mai-portfolio-backend`。前端检查点/迁移由其 Agent 协调，不能擅自切换其分支。

## 复跑入口

`scripts/integration/verify-baseline.mjs`、`verify-system.mjs`、`verify-editor.mjs`、`verify-import-rollback.mjs` 分别运行，不能并行修改相同账号会话。要求 BASELINE_ISOLATED=1、BASELINE_OWNER_PASSWORD、BASELINE_PUBLIC_KEY_FILE、BASELINE_OUTPUT_DIR；额外脚本固定 loopback 3000和隔离数据库/端口，要求 ACCEPTANCE_DB=system_acceptance_20261013、ACCEPTANCE_PSQL、ACCEPTANCE_JAVA、ACCEPTANCE_OSS_CLASSPATH、ACCEPTANCE_PYTHON、PLAYWRIGHT_MODULE_PATH、BROWSER_EXECUTABLE及环境注入 OSS 配置。

先创建新的专用库和 Redis 前缀、临时 RSA/账号并启动 9333/3000；Nuxt公钥配置为 SPKI Base64，不是完整 PEM。所有写入入口共用 assert-isolated-backend 的随机标签探针，先证明HTTP确实读取该隔离库再写入，探针最终删除。测试重复登录数量较多，隔离实例额度为登录50/注册20；生产默认值未改。OssAcceptanceCleanup 使用打包应用 BOOT-INF/lib 中现有 SDK 依赖，校验随机对象键清单，只操作本轮对象。报告不包含Cookie、密文、签名地址或 AccessKey。

详见 [Git 规范](../development/git-workflow.md) 与 [待审核 CI 模板](../development/ci-pr.example.yml)。修复与文档通过本轮 PR 审核后才能进入 main；未恢复新的后端业务开发。
