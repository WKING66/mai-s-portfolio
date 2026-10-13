# 博客导入 Hotfix

修复分支 `feature/blog-import-workflow`，父分支 `feature/site-visual-stabilization`（`e285324`）。分支命名遵循 AGENTS.md 的 feature 前缀要求。检查所有分支上的导入组件历史后，确认当前分支含最新目录导入实现（`12f5693`、`70b6eba`），不是机械套用阶段 1B 的基点。维护者的 `docs/architecture/document-import-export.md`、前后端 `.env` 不修改、不暂存。无合并、推送或其他工作区修改。

## 业务事实

`POST /api/v1/blogs/imports` 调用已有 Parser/资源解析器后，直接创建 Document 版本、资源关联和持久化博客草稿。成功返回完整 Blog，再导航 `/admin/blogs/{id}`；不是把文件内容填到未保存表单。导入的 slug 为空；`PATCH /api/v1/blogs/{id}` 已支持保存 slug，160 字符上限，数据库 `uk_blog_post_slug_ci` 以 LOWER(slug) 保证唯一性。首次发布后由后端 publishedVersionId 规则锁定 slug，后续下架也不解锁。不存在既有自动生成规则。

维护者明确要求标题与 Slug 独立，不按标题语言分类；本轮不自动音译、改写标题或悄悄覆盖已输入的 Slug。

## Commit 1：布局和选择状态

根因：横向卡片式 RadioGroup 不换行，其子项内容最小宽度撑开 18rem 侧栏；文件/正文 Select 与提示区域也缺少最小宽度及长文本处理。两种模式保存了独立文件变量，但切换只修改 mode，没有清空旧变量、原生输入和权利选项，返回旧模式即显示旧选择。

修复：

- 导入局部 Grid/Flex 设置 min-width:0，单选卡片 flex-wrap，窄容器纵向排列。长文件名截断并用 title 保留完整名称；完整路径及资源提示安全换行，Select 菜单按 Trigger 宽度展开并换行。没有全局隐藏横向溢出或裁切面板。
- `useBlogImportSelection` 只持有待导入文件：mode、正文、附件、目录文件、bodyPath、rights 与重挂载 revision；来源、候选正文、相对资源路径和校验是 computed。保持正文目录及子目录的关联规则，多正文必须显式选择。
- 显式 changeMode/reset 清空两种模式全部临时选择和权限选项；revision 重挂载 FileUpload 及折叠区，原生目录 input.value 清空。没有写只读 FileList、没有多个 watch 同步正文。清空选择可随时重新开始；取消系统目录选择保留原有效选择。
- 当前没有前端异步 Markdown 解析：解析、上传归档、创建草稿在导入 POST 内。POST 可能已经持久化，不能取消后假装没创建；busy 同步锁定模式、输入和提交。每次导入使用任务序号，组件卸载使其失效，旧结果、错误与 finally 不能导航新页面、发旧 Toast 或改变新 Loading。没有新增破坏性资源清理。file-image=false，不创建图片预览 Blob URL。

验证：类型检查、生产构建通过；26 个 Vitest 文件、264 项通过。Edge 真实管理页、生产构建 3000、后端 9333；API fixture 只在隔离浏览器拦截，不启动额外应用服务或写真实数据。实际侧栏宽度 240/280/320/390px，覆盖 Markdown/目录模式、长文件名、长路径、30 个资源、多个正文、空正文/无正文目录、附件、模式往返、快速切换、同一文件重选、权利重置、重复提交、离开后旧请求失败和 390px 移动端，全部通过。正文相对资源规则、重复文件及不支持格式也有单元断言。

第一批截图：[Markdown 240px](screenshots/blog-import-hotfix-20261011/markdown-240.png)、[目录 240px](screenshots/blog-import-hotfix-20261011/folder-240.png)、[目录 390px](screenshots/blog-import-hotfix-20261011/folder-390.png)、[移动端](screenshots/blog-import-hotfix-20261011/folder-mobile.png)、[桌面管理页](screenshots/blog-import-hotfix-20261011/import-desktop.png)、[空正文](screenshots/blog-import-hotfix-20261011/empty-file-error.png)。组件局部截图仅临时隐藏固定 Header，避免长面板截图被页头遮挡；宽度与交互断言始终在未隐藏的真实页面执行。展开菜单截图与桌面完整页面不隐藏 Header。

源码：`frontend/app/components/BlogImportPanel.vue`、`frontend/app/composables/useBlogImportSelection.ts`；测试：`frontend/app/test/blogImportSelection.test.ts`、`frontend/app/test/blogImportWorkflow.integration.mjs`。原生 FileUpload 能力复用 Nuxt UI，没有新依赖或 API 契约修改。

## Commit 2：可见的 Slug 编辑和发布闭环

根因：导入 API 持久化空 Slug，已有更新接口支持修改；编辑入口却藏在右侧设置卡片，容易被侧栏布局和滚动位置掩盖。发布按钮只检查未保存状态，没有提示导入后必填的标识；原有“小写字母、数字、连字符”描述也不符合后端仅限制长度的实际规则。

将既有文章标识输入移到标题正下方，不复制一套导入元数据表单。标题和 Slug 始终独立，没有按语言分支、音译、随机值或自动覆盖。空标识仍可保存草稿；首次发布前须补全标识、标题、正文并保存。输入展示公开路径与保存要求，保留原有脏状态、操作互斥和后端最终发布校验。

数据流保持：导入 POST 返回持久化草稿 → 导航现有 BlogEditor → 手动编辑标题/Slug → PATCH 携带既有 version 和完整 Markdown/资源引用 → 成功后接收快照 → 重新读取回显 → 发布 POST 使用已保存 version → 原有 `/blog/{slug}` 阅读页。未发布草稿可以改名；不存在旧地址重定向，首次发布后锁定，随后下架也不解锁。后台续签只更新展示字段，不覆盖手动输入。

`frontend/app/utils/blogSlug.ts` 仅校验前端 URL 单路径段可用性：沿用 160 字符边界，拒绝空白、控制/不可见字符、`/ ? # \\ %` 与 `.` / `..`。允许普通 Unicode 和已有冒号标识，不截断或修改输入；已发布的锁定标识不因新前端检查影响旧文章编辑。后端仍负责长度、唯一性、权限及发布条件；本轮不宣称后端新增了同样的格式约束。

保存失败保留表单和服务端快照，显示持久的错误提示及 Toast，可修改重试。Nuxt UI Alert 默认没有警报角色，显式补充 `role="alert"`。现有后端把数据库冲突和版本冲突都归为通用 DATA_CONFLICT/409，前端诚实提示“文章标识可能已被使用，或草稿版本已变化”，不猜测为确定的重名、不读取本地列表判断唯一性、不暴露 SQL。若要精确区分冲突，后续需要后端细分业务错误码。

## 最终验证

| 检查 | 实际结果 |
| --- | --- |
| Nuxt 类型检查 | 通过（最后补充 Alert 角色后重新执行） |
| Vitest | 27 文件、267 项通过；新增选择状态 5 项及 Slug 3 项 |
| 生产构建 | 通过；存在既有依赖 DEP0155 与 chunk 大小警告，未改变依赖 |
| Edge / Playwright Hotfix 回归 | 通过；真实生产页面 3000，240/280/320/390px 实际面板及 390px 移动端 |
| 文件夹提交 | 正文显式选择，30 个资源以 `images/{n}.png` 相对路径提交，正文目录外文件不提交 |
| Slug 闭环 | 默认留空可编辑；手动修改、改标题保留、非法值拦截、409 与 500 后输入保留、重试保存、刷新回显、修改其他字段保留、发布访问、旧未发布标识 404、首次发布及下架后锁定均通过 |
| SSR / Hydration | 真实 Nuxt SSR 外壳与客户端恢复没有水合警告，浏览器未处理异常 0；API fixture 仅覆盖隔离浏览器的请求 |
| 既有博客浏览器回归 | 2 项通过：新建/保存/发布/下架、公开发布版本与后续草稿隔离、未保存操作限制、导入与 Markdown/ZIP 导出 |
| 真实 PostgreSQL / OSS 端到端 | 本轮未执行，不标记通过；数据库唯一性与服务端生命周期规则通过现有源码确认 |

首次完整浏览器检查因保存错误 Alert 缺少 role 导致可访问性定位超时；已补充角色、重新类型检查/生产构建并全量重跑 Hotfix 浏览器脚本通过。最终证据在 [browser-results.json](screenshots/blog-import-hotfix-20261011/browser-results.json)。浏览器写入全部在内存 fixture，包含 CSRF、version、Markdown 原文及 resource 引用断言；没有向维护者数据库创建测试文章，没有启动其他应用端口。前端 3000 已运行最终生产构建，后端 9333 沿用现有项目服务。

截图全部来自 Edge：

- 文件模式及窄容器：`markdown-{240,280,320,390}.png`。
- 目录模式及展开选择器：`folder-{240,280,320,390}.png`、`folder-menu-{240,280,320,390}.png`。
- 移动端及错误状态：`folder-mobile.png`、`folder-no-markdown.png`、`empty-file-error.png`。
- [导入后的 Slug 编辑区](screenshots/blog-import-hotfix-20261011/imported-slug-empty.png)、[格式错误](screenshots/blog-import-hotfix-20261011/slug-invalid.png)、[服务端冲突提示](screenshots/blog-import-hotfix-20261011/slug-conflict.png)。
- [保存并刷新回显](screenshots/blog-import-hotfix-20261011/slug-saved-reloaded.png)、[发布后访问](screenshots/blog-import-hotfix-20261011/published-slug-reader.png)、[移动编辑页](screenshots/blog-import-hotfix-20261011/slug-mobile.png)。

## 修改清单与边界

- 修改：`frontend/app/components/BlogImportPanel.vue`、`frontend/app/components/BlogEditor.vue`。
- 新增：`frontend/app/composables/useBlogImportSelection.ts`、`frontend/app/utils/blogSlug.ts`。
- 新增测试：`frontend/app/test/blogImportSelection.test.ts`、`frontend/app/test/blogSlug.test.ts`、`frontend/app/test/blogImportWorkflow.integration.mjs`。
- 新增交付：本报告及 `docs/testing/screenshots/blog-import-hotfix-20261011/` 的真实浏览器截图和结果。
- 没有删除文件、新增依赖、改后端/API 契约/Markdown 格式/正式编辑器/Document 或 Asset 权限；维护者文档及两份 .env 哈希保持原值。

未解决及取舍：

1. 不提供自动 Slug 建议：现有业务没有生成策略，遵循维护者要求直接编辑，不按标题语言分类。空值提示和发布保护消除无法找到编辑入口的问题。
2. 实际数据库冲突、服务端 Parser 的文件读取异常、资源归档与 OSS 保存没有在真实数据上重跑。已有后端生命周期测试会清空专用测试库，当前未配置该测试库，不以维护者开发库替代。浏览器 fixture 验证客户端提交、错误恢复、资源引用保真及发布状态，不冒充服务端端到端测试。
3. 导入 POST 是持久化操作，处理期间禁止切换或重复提交；卸载后忽略过期响应，但不假装撤销已创建草稿。已有管理列表可找回已创建的草稿。
4. 未保存修改提示、版本号提交和发布/下架/导出限制沿用现有编辑器；没有新增版本历史或离页确认业务。首次发布后不能改 Slug，故不引入重定向机制。

Git：第一批提交 `4dada02`（`fix(blog): isolate import selections and fit narrow panels`）；第二批提交 `fix(blog): expose slug editing and close publication workflow`（本报告随同提交）。分支 `feature/blog-import-workflow`，父分支 `feature/site-visual-stabilization@e285324`。无合并和推送，等待维护者验收。
