# 文档导入导出：注释、常量与链路文档验收记录

日期：2026-10-09。功能分支：`feature/document-comments-flow-docs`。父分支：`feature/blog-admin-import-ui`，起点 `fe334d8`（`remove slug match`）。开始时工作区干净；本轮直接修改维护者主工作目录，没有新增工作树，没有恢复父分支已删除的 slug 正则规则。

完整链路及七张流程图见 [文档导入导出设计与运行链路](../architecture/document-import-export.md)。独立图源码位于 [diagrams/document](../architecture/diagrams/document/)，与主文档中的 Mermaid 图块一致；主文档是图的编辑来源。

## 1. 功能正确性

本轮整理博客 Document 链路及其调用的统一 Asset、前端 API 和资源 URL 刷新逻辑，补充业务解释、集中重复常量。没有新增接口、迁移数据模型或改变 MD/ZIP 导出选择规则：只导出正文或没有资源时是 `.md`；选择包含资源且确有资源时是 ZIP。

一处输入边界修正：上传 MIME 原来允许 120 个字符，实际 `media_asset.mime_type` 字段仅容纳 100 个字符。现在由 `AssetPolicy.MAX_CONTENT_TYPE_LENGTH = 100` 统一限制，在写数据库或上传 OSS 前拒绝超长元数据。新增 101 字符 MIME 回归用例验证错误码和无外部副作用。文件名限制保持 255，不调整已有文件字节、图片格式或像素上限。

本轮没有再次执行真人 OSS 上传和下载。前一轮真实 Nuxt → Spring Boot → PostgreSQL/Redis/OSS 联调，以及维护者确认“导出功能正常”的记录见 [博客管理联调记录](blog-admin-ui-20261009.md)。两轮验证不能混写为本轮重新完成的真人验收。

## 2. 设计与职责边界

注释重点解释业务事实、为什么这样做及失败语义，而非给每条赋值添加描述：

| 位置 | 补充的关键解释 |
|---|---|
| BlogController / BlogService | HTTP 边界、PUBLIC/MANAGE 投影、权限与发布快照；导出在提交响应前准备 |
| BlogServiceImpl | 资源准备与短数据库事务分离、并发锁、保存失败只补偿本次新资源、发布/下架指针 |
| DocumentServiceImpl / 领域实体 | 不可变正文版本、当前与发布版本、内容版本和锁版本差别、资源关联及读取快照 |
| Parser / ResourceResolver | 严格 UTF-8、正文归一化、相对路径、AST 排除代码块、资源去重、失败资源仍可保存草稿 |
| RemoteResourceFetcher | 授权抓取、SSRF 约束、共享抓取预算、格式初检与 Asset 最终检验的边界 |
| Renderer / ExportService | 派生 URL 不持久化、HTML 清理、MD/ZIP 计划、流所有权、中途失败不能伪装完整 ZIP |
| AssetFileService / 上传组件 | 统一文件能力、元数据容量、授权调用者负责业务访问权限及关闭内部读取流 |
| BlogImportPanel / BlogEditor | 选择体验与服务端最终校验、输入缓冲与已保存快照、续签不能覆盖未保存输入 |
| 前端 blogs/request API | multipart 路径来源、Blob 错误 JSON 解码、文件名、下载地址生命周期与浏览器内存边界 |
| 公开列表/详情与 URL 刷新 | 公开投影缓存、签名有效期、缓存检查与刷新调度共用余量 |

Mapper 继续只承担持久化操作，没有为了“补注释”将业务判断挪入 Mapper、DTO 或 Controller。配置参数保留于已有 `DocumentProperties` / `AssetUsage`，固定协议和表容量位于对应模块常量类；没有建立通用常量仓库或额外抽象层。

## 3. 魔法值处理及 Source of Truth

| 原散落表达 | 现在的来源 | 说明 |
|---|---|---|
| 默认页 1、数量 12、最大页 10000、最大数量 50 | `BlogPolicy` | Controller 默认值与 Service 校验规则使用同一来源 |
| 访客下载 0/1 | `BlogPolicy.VISITOR_DOWNLOAD_DISABLED/ENABLED` | 表字段语义；没有另加一套状态枚举 |
| `.md` 与文件名减 3 | `DocumentPolicy.MARKDOWN_EXTENSION` 及其长度 | 解析器和导出器共用；不依赖写死后缀长度 |
| `asset://` 与截取 8 字符 | `DocumentPolicy.ASSET_REFERENCE_PREFIX` 及其长度 | 上传资源引用协议 |
| 资源 URI 2048、key 36、role 0、首版 1 | `DocumentPolicy` | 与表约束/现有文档协议对齐；资源类型和关联角色是不同概念 |
| 16384 字节读取/复制缓冲 | `DocumentPolicy.IO_BUFFER_BYTES` | 16 KiB I/O 缓冲，不能解释成文件大小限制 |
| 文件名 255、MIME 120 | `AssetPolicy`：255 / 100 | MIME 修正对齐数据库字段容量 |
| HTTP 状态码、PNG/JPEG/二进制 MIME | 已有 HTTP / Spring 库常量 | 复用依赖，不新造协议常量 |
| HTTP/HTTPS 前缀、默认端口、不可用资源链接 | 相应组件内具名常量 | 只服务该组件的规则就近保存，不扩大公共 API |
| 前端首尾分页边界、12/20/4、PUBLIC/MANAGE、缓存前缀 | `frontend/app/constants/blogs.ts` | 公共列表/首页展示数量与后端默认值含义区分 |
| 前端 `asset://`、`resource://` | 对应 Asset/Document 常量 | 稳定引用不能替换成签名地址再保存 |
| 缓存检查与刷新调度各写 30000 | `constants/assets.ts` 的 `ASSET_URL_REFRESH_LEAD_MS` | 同一签名刷新规则共用；Blob URL 释放时间仍有独立来源 |
| 浏览器最大定时器延迟 | 刷新组件内 `MAX_BROWSER_TIMEOUT_MS` | 明确平台上限，避免与业务有效期混淆 |

数量、字节、网络超时和重定向上限仍由已有配置决定。位掩码、网络前缀、AST 标题级别和普通算法计数保留必要解释，没有将所有数字机械包装为公共常量。前后端跨语言契约尚未自动生成，服务端仍是最终校验来源。

## 4. 当前支持的扩展点

- 图片、附件继续复用现有统一 Asset 上传、内容校验、OSS 存储及签名 URL 投影；新增博客图片、项目截图或头像用途不需要复制上传 Controller/Service。
- 新文档格式从现有 `DocumentParser → ParsedDocument` 边界接入；统一资源准备、Document 版本、Blog 发布及展示不需要复制。多格式选择方式需在实际增加第二个 Parser 时确定。
- 更换存储适配仍走既有 Asset 文件服务与存储边界；Blog 不直接调用 OSS SDK。没有为了此次注释工作新增存储接口。
- 后续 RAG 可以消费 `DocumentService.read(id, versionId)` 的稳定正文和资源快照，不应重新解析原上传文件。

## 5. 主动没有提前实现的扩展

本轮没有增加 DOCX/PDF Parser/Exporter、Parser Registry、导出任务队列、前端流式落盘协议、Chunk、Embedding、向量存储、MQ、知识库同步或 RAG 状态机。当前调用方只有 Markdown 博客生命周期，新增这些结构不能解决此次注释与规则收敛问题。

没有修改父分支删除的 slug 正则；没有顺便修改公网地址安全策略或数据库中未接入的任务表。文档记录其现状和限制，避免用注释掩盖尚未实现的功能。

## 6. Maintainability Review

| 检查 | 结果与实际边界 |
|---|---|
| 新增同类资源需要修改多少位置 | 当前上传仍一套核心实现；具有新规则的用途修改用途约束/绑定方，不能复制整个上传流程 |
| 新增文档格式是否复制主流程 | 不复制 Blog/Document/Asset 生命周期；新增 Parser 并在实际多格式需求处接入选择 |
| 替换外部依赖是否影响业务代码 | 存储变化留在既有 Asset/存储适配边界；不要求逐个修改 Blog 调用方 |
| 是否重复业务规则 | 本轮消除分页、协议前缀、元数据容量、签名刷新余量的散落；跨语言输入上限仍需同步 |
| 是否有仅为测试通过的生产分支 | 未新增测试文件名、账号、文章 ID 或固定测试资源特殊处理；MIME 边界依据数据库容量 |
| Source of Truth 是否明确 | 正文在 DocumentVersion，绑定在版本资源关系，位置在 Asset；Blog 的当前/发布指针决定展示，URL 是派生值 |
| 失败路径是否完整且被解释 | 输入拒绝、资源失败状态、并发冲突、事务回滚补偿、预览失败、响应提交前/后的导出失败均有说明 |
| 配置、异常、日志是否符合现有规范 | 沿用配置校验、ApiException/项目错误码和已有日志；未记录凭据、签名 URL 或正文到新日志 |
| 下一次需求是否被迫复制粘贴 | 没有新增场景上传 Service、万能 Service 或第二套 admin/public 业务实现 |

此检查不声称消除了项目所有历史债务。当前需要持续处理的限制见下一节与主文档。

## 7. 技术债务与使用限制

- 前端下载完整 Blob，导出较大 ZIP 时仍可能占用较多浏览器内存；后端流式输出不等于浏览器流式落盘。
- 前后端契约常量分属两种语言，仍需维护同步。
- 公网地址过滤目前偏保守，部分合法地址可能被拒绝；应以独立安全需求评估其精度。
- Asset 暂无通用孤儿回收链路，失败记录、未绑定上传或跨存储删除失败可能保留。
- `.md` 中的私有 OSS 签名链接会过期；ZIP 将资源打包后才可离线使用。
- 数据库未接入的任务表、slug 前端字符提示与现行后端规则差异已记录，未在本轮恢复旧规则或扩展异步系统。

## 8. 测试与构建

仅执行相关检查，没有以全量无关测试替代当前范围的验证。

| 检查 | 结果 |
|---|---|
| `AssetUploadPipelineTest` | 18 项通过，包含 MIME 超表容量的新增回归 |
| `DocumentPipelineTest` | 12 项通过，覆盖解析、资源、渲染及 MD/ZIP 导出等现有场景 |
| 前端 `assetUrls.test.ts` | 2 项通过 |
| 前端 `request.test.ts` | 6 项通过 |
| Nuxt typecheck | 通过 |
| Nuxt production build | 通过 |
| 后端 launch 及依赖模块离线 package | 通过，构建步骤跳过重复测试 |
| 文档链接、七份图块/独立源码、Git 空白检查 | 通过；未执行 Mermaid 图形渲染验收 |

相关测试合计 38 项通过。首次 `DocumentPipelineTest` 在断言完成后遇到 Windows 用户临时目录删除 `AccessDeniedException`，该次 Maven 执行失败；没有更改断言或忽略错误。将 JVM 临时目录设置为项目忽略的专用目录后，仅重跑该测试类，12 项通过、0 失败、0 错误。

后端检查入口（在 `backend` 目录，Java 21、现有 Maven 离线缓存）：

```powershell
mvn -o -pl mai-portfolio-modules/mai-portfolio-module-blog/mai-portfolio-module-blog-service,mai-portfolio-modules/mai-portfolio-module-asset/mai-portfolio-module-asset-service -am -Dtest=DocumentPipelineTest,AssetUploadPipelineTest -Dsurefire.failIfNoSpecifiedTests=false test

# 首次运行的 Windows 临时目录清理失败后，重跑 Document 测试：
mvn -o -pl mai-portfolio-modules/mai-portfolio-module-blog/mai-portfolio-module-blog-service -am -Dtest=DocumentPipelineTest -Dsurefire.failIfNoSpecifiedTests=false '-DargLine=-Djava.io.tmpdir=F:/code/ai/codex/projects/mai-portfolio/.local-backups/document-comments-test-temp' test

mvn -o -pl mai-portfolio-launch -am -DskipTests package
```

前端检查入口（在 `frontend` 目录，现有 Node 运行时）：

```text
node node_modules/vitest/vitest.mjs run app/test/assetUrls.test.ts app/test/request.test.ts
node node_modules/nuxt/bin/nuxt.mjs typecheck
node node_modules/nuxt/bin/nuxt.mjs build
```

测试日志与构建产物保持在忽略目录，不提交本地配置、凭据或日志。代码、回归测试及本轮文档在当前功能分支提交后停止，等待维护者审核；不合并、不推送。
