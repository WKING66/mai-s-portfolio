# 技术研究与决定（Phase 0）

本文件记录首版需要锁定的技术取舍。来源优先采用产品与项目官方文档；具体依赖补丁号在创建工程时以兼容性测试锁定。

## 1. 前后端边界与页面呈现

- **Decision**: 独立 Nuxt 4 前端 + Spring Boot 4.1 REST 后端。公开首页、项目列表、博客列表及文章采用 Nuxt SSR；`/admin` 保留在同一前端，由 Spring Boot 认证和授权。Nuxt 不实现第二份业务规则。
- **Rationale**: 满足站点主人指定的前后端分离，并让公开正文在初始 HTML 中可见；Nuxt 的服务端数据获取可等待后端公开 API。Java 21 与 Spring Boot 4.1、Node 24 LTS 与 Nuxt 4 的兼容范围均有官方说明。
- **Alternatives considered**: 纯 Vue SPA 更少服务端呈现逻辑，但核心正文需等待脚本，和本项目 SEO 目标不符；全站静态生成在站内发布后需要额外重建流程；Spring MVC 模板单体不符合本轮前后端分离选择。
- **Sources**: [Nuxt 渲染](https://nuxt.com/docs/4.x/guide/concepts/rendering)、[Nuxt 数据获取](https://nuxt.com/docs/4.x/getting-started/data-fetching)、[Nuxt 安装条件](https://nuxt.com/docs/4.x/getting-started/installation)、[Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)。

## 2. SEO 与 GEO 的可验证边界

- **Decision**: 公开路由生成独立 `title`、description、canonical、Open Graph；仅发布内容进入 sitemap，后台和草稿不可索引；文章详情输出与可见正文一致的 Article/BlogPosting 结构化数据。内容层面保留清楚的作者、日期、项目贡献与可核查的一手复盘。部署后再做搜索平台验证与提交。
- **Rationale**: 服务端可读内容、规范 URL、站点地图和结构化数据可本地验证。GEO 不另做“保证 AI 引用”功能：Google 官方 AI 搜索说明仍强调有价值的原创内容与基础 SEO，不要求专门的 `llms.txt` 或特殊 AI 标记；搜索收录及排名无法由本地代码保证。
- **Alternatives considered**: 强制生成 `llms.txt`、为 AI 重写重复页面、堆砌关键词，均无可验证的首版收益；只在客户端设置元数据会降低原始响应的可检查性。
- **Sources**: [Nuxt SEO 元数据](https://nuxt.com/docs/4.x/getting-started/seo-meta)、[Google 文章结构化数据](https://developers.google.com/search/docs/appearance/structured-data/article)、[Google 站点地图](https://developers.google.com/search/docs/crawling-indexing/sitemaps/build-sitemap)、[Google AI 搜索指南](https://developers.google.com/search/docs/fundamentals/ai-optimization-guide)、[百度搜索优化指南](https://zy.baidu.com/act/seo?isResponsible=1)。

## 3. MySQL 数据访问与缓存

- **Decision（2026-09-26 用户确认，覆盖早期决定）**: MySQL 8.4 LTS + SQL 迁移 + MyBatis-Plus 的 Spring Boot 4 starter + Druid 的 Spring Boot 4 starter。Mapper 必须把“已发布”过滤和下载授权写在可审查的服务流程中；不因为 ORM 存在而放松服务层校验。首版不运行 Redis/Caffeine；若将来给 Sa-Token 接 Redis，用户要求兼容 Redis **服务端 3.2.1**，需用该真实版本验证连接、命令、序列化、TTL，不能仅凭客户端最低版本说明推断 Sa-Token 插件兼容。
- **Rationale**: 用户已明确选择 MyBatis-Plus、Druid。官方均有 Boot 4 starter，但“可取得依赖”不等于本项目组合经过启动、事务和分页实测。Redis 3.2.1 不在 Redis 当前支持版本列表中，正式运行前还要单独评估升级与安全风险；首版无 Redis 功能，因此不引入旧实例作为运行依赖。
- **Alternatives considered**: Spring JDBC 是早期更小的选择，现由用户确认的 MyBatis-Plus 覆盖；若实际兼容性实验失败，再更新计划并说明，不静默替换。Redis/Caffeine 仍留在第二版有真实需求时评估。
- **Sources**: [MySQL LTS 策略](https://dev.mysql.com/doc/refman/8.4/en/mysql-releases.html)、[MyBatis-Plus Boot 4 安装](https://baomidou.com/getting-started/install/)、[Druid Boot 4 发布](https://github.com/alibaba/druid/releases)、[Spring Data Redis 最低服务端版本](https://docs.spring.io/spring-data/redis/reference/redis/getting-started.html)、[Redis 当前支持版本](https://redis.io/docs/latest/operate/oss_and_stack/install/version-mgmt/)。

## 4. 媒体存储与读取

- **Decision**: 真实图片归档到私有 OSS Bucket；MySQL 仅保存媒体 ID、对象键、来源、校验值、状态和关联。站点通过稳定的 `/api/v1/media/{id}` 读取入口先做内容状态检查，再代理读取图片；正文不存会过期的签名 URL。开发环境可用本地存储实现同一媒体合同，交付前必须用真实 OSS 验证上传、读取、撤稿隔离。
- **Rationale**: 统一入口适用于本地图片与网络图片，也使草稿/下架媒体不能因直接对象地址泄漏。私有对象的签名 URL 会到期，且持有者在有效期内仍能访问，不适合持久写进 Markdown。代理会增加应用带宽开销，先以低流量首版换取清楚的权限边界，实际流量增长后再评估 CDN/公开副本。
- **Alternatives considered**: 全 Bucket public-read 容易暴露草稿；把签名 URL 永久写进文章会失效；所有图片存在 MySQL 增加数据库负担；本地文件仅可作开发模式，不满足最终 OSS 约束。
- **Sources**: [OSS 权限](https://help.aliyun.com/zh/oss/user-guide/permissions-and-access-control-overview)、[私有对象签名访问](https://help.aliyun.com/en/oss/developer-reference/download-using-a-presigned-url)、[OSS 计费](https://help.aliyun.com/en/oss/billing-overview)、[OSS 域名与预览](https://help.aliyun.com/zh/oss/manage-a-domain/)。

## 5. Markdown 图片导入与网络抓取

- **Decision**: 首版内容格式仍仅为 Markdown。纯 `.md` 可直接导入；若引用本地多级图片，使用包含一篇 `.md` 和相对图片树的 ZIP 作为传输容器，保留路径（不是增加 Word/PDF 等文章格式）。所有图片进入待处理状态，校验成功后入 OSS 并将 Markdown 引用规范化为站内媒体 ID。网络图片仅由已登录站点主人显式确认归档，后端受限抓取；不支持的来源要求手动上传，绝不静默保留第三方图片链接。
- **Rationale**: 浏览器仅提交一个 `.md` 文件时无法自动读取该文件引用的其他本地路径；ZIP 能跨浏览器保留多级目录。远程抓取必须抵御 SSRF：只接受 HTTP(S)，禁止凭据和非预期端口/跳转，解析与连接均须限制到公网地址，设置超时、字节与像素上限，校验真实图片内容。ZIP 需限制展开大小、条目数并拒绝目录穿越。所有失败在草稿预览逐项显示。
- **Alternatives considered**: 仅选多张散图无法稳定区分重名和相对目录；目录选择器可作为后续便捷入口但不作为唯一跨浏览器路径；浏览器直接访问远程图片受 CORS 与来源策略限制；无约束的后端 URL fetch 不可接受。
- **Sources**: [OWASP SSRF 防护](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html)、[OWASP 文件上传](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html)、[OSS 上传授权](https://help.aliyun.com/zh/oss/developer-reference/authorize-access-6)。

## 6. Markdown 渲染与三格式导出

- **Decision**: 正文只存 Markdown。先用仓库中的含两张本地 PNG 的样本，再补一张可控网络图片和中文/表格样本，验证 flexmark-java 的解析、DOCX 与 PDF 模块。`.md` 导出保留正文及站内受控图片引用；DOCX/PDF 只从已归档媒体读取图片字节，不能由转换器任意拉取 URL。若验证失败，重新评估 Pandoc 等方案，不提前绑定大依赖集合。
- **Rationale**: flexmark 有 Markdown AST、DOCX 和 PDF 扩展，适合共用一份正文，但官方明确生成 HTML 不自动消毒；公开渲染要禁用或过滤原始 HTML 并校验链接。PDF 中文字体、代码块、链接和图片保真度必须通过真实文件验证。
- **Alternatives considered**: 自己用低级 DOCX/PDF API 重写排版成本高；Pandoc 可作备选，但 PDF 还需额外引擎与命令执行隔离；只导出 Markdown 不符合已确认首版范围。
- **Sources**: [flexmark 项目](https://github.com/vsch/flexmark-java)、[DOCX 转换](https://github.com/vsch/flexmark-java/wiki/Docx-Renderer-Extension)、[flexmark HTML 安全说明](https://github.com/vsch/flexmark-java/wiki/Usage)、[PDF 中文字体问题](https://github.com/danfickle/openhtmltopdf/issues/174)、[OWASP XSS 防护](https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html)。

## 7. 身份、接口与第二版 Agent 边界

- **Decision（2026-09-26 用户确认，覆盖早期决定）**: 单站长账号改用 Sa-Token 的 Spring Boot 4 Web MVC starter、HttpOnly 同源 Cookie 和后端明确实现并测试的 CSRF 防护；不把 Sa-Token 当作自动提供 CSRF。浏览器仍通过同源 `/api/v1` 访问后端，公开 API 只返回已发布资料。第二版 Agent 只通过受控只读接口读取发布内容。
- **Rationale**: 用户确认 Sa-Token；单人管理端仍不需要 JWT/刷新令牌体系。鉴权与内容许可必须在 Spring Boot 一处执行。首版先用 Sa-Token 内置会话存储，不启动 Redis；未来 Redis 3.2.1 适配是单独的兼容性门槛。
- **Alternatives considered**: 早期 Spring Security 方案已被用户选择覆盖。浏览器 JWT、Nuxt 直接读库、首版 Redis/Caffeine/向量库均不采用。
- **Sources**: [Sa-Token Boot 4 starter 与版本](https://github.com/dromara/sa-token/releases)、[Sa-Token 官方集成示例](https://github.com/dromara/sa-token/blob/dev/sa-token-doc/start/example.md)、[Spring CSRF 原理](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)。

**工程预检（尚非完整兼容性验收）**: 当前脚手架锁定 Spring Boot 4.1.1、Sa-Token 1.46.0、MyBatis-Plus 3.5.17、Druid 1.2.28、springdoc 3.0.3；Java 21 上的编译与无真实数据库连接的应用上下文启动测试已通过。CSRF 写请求、真实 MySQL 8.4 事务/分页、Swagger HTTP 输出和未来 Redis 3.2.1 连接均尚未通过实测，不能据此宣称兼容完成。

## 8. Swagger/OpenAPI 接口核对

- **Decision**: Spring Boot 后端采用与 Boot 4 兼容的 `springdoc-openapi-starter-webmvc-ui` 3.x，生成 `/v3/api-docs` 和 `/swagger-ui.html`。公开与管理接口都写明请求/响应 DTO、会话认证、CSRF 和关键错误；本地开发可查看并试用。正式环境默认关闭文档 UI 和规范端点，确需保留时须独立鉴权，不向访客开放管理接口交互操作。
- **Rationale**: 用户需要用可视化接口文档确认开发进度；springdoc 官方明确 3.x 对应 Spring Boot 4，并提供 OpenAPI JSON 与 Swagger UI。文档不是接口测试的替代品，验收需对照实际请求验证。
- **Alternatives considered**: 手写 Swagger 静态文件容易和控制器漂移；完全公开生产 Swagger UI 会增加管理接口的暴露面。
- **Sources**: [springdoc 官方文档](https://springdoc.org/)、[Spring Boot 4 兼容说明](https://github.com/springdoc/springdoc-openapi)。

## 尚需在实现中验证、但不阻塞规划的门槛

1. flexmark 的 DOCX/PDF 输出是否满足中文字体、代码、图片和表格的质量要求；先做转换小实验，再锁依赖。
2. Nuxt 与后端在本地同源代理下的会话/CSRF 行为；用真实登录与草稿访问测试。
3. OSS 实际账号、区域、流量成本和域名访问方式；本地可先使用模拟存储，但图片功能交付前要通过 OSS 环境测试。中国内地 Bucket 的自定义域名在线预览可能涉及备案，部署阶段再核对。[阿里云域名说明](https://help.aliyun.com/zh/oss/manage-a-domain/)
4. 网络图片的授权与抓取限制；若特定来源不能安全归档，由站点主人手动上传该图片，不放宽 SSRF 检查。
5. 脚手架建成后锁定 Spring Boot 4.1 与 springdoc 3.x 的兼容补丁版本，实际打开 Swagger UI、读取 OpenAPI JSON，并验证管理接口的会话和 CSRF 说明。
