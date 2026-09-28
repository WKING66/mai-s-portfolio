# 本地运行与验收指南（Phase 1）

> **历史计划提示（2026-09-27）**：下文 MySQL、`article` 单正文、Word/PDF 导入导出及匿名访客下载步骤已失效，不能作为当前实施或验收依据。现行启动方法见仓库根目录 [README](../../README.md)，PostgreSQL 切库结果与剩余风险见 [切库审核点](postgresql-document-transition.md)，后续模块以 [有效任务 T063–T073](tasks.md) 为准。

**当前状态**：已开始建立 `frontend/` 与 `backend/` 工程，功能尚未完成；以下验收流程只有逐项实测后才能标记通过。服务器和域名选择不属于本地首版验收。

## 前置条件

- Java 21、Node.js 24 LTS，以及站点主人本机已有的 MySQL；当前检测 MySQL 8.0.34 监听 `127.0.0.1:13306`。建表与迁移仅在专用 `portfolio_dev` 测试库执行；不得操作已有业务库或本机 Docker。模型原定 MySQL 8.4 目标需在正式环境版本确定后复核。
- 实现阶段提供 `frontend/.env.example` 与 `backend` 的本地配置示例；复制到被 Git 忽略的本地配置后填写站长初始密码。任何 OSS AccessKey、密码或会话密钥不得提交到仓库。
- 基础本地流程可用 `MEDIA_STORAGE=local` 验证；图片功能交付前，另用 `MEDIA_STORAGE=oss` 和测试 Bucket 执行真实集成验收。OSS Bucket 保持私有。
- 开发后端只监听 `127.0.0.1`，允许本机 HTTP 调试。密码请求体采用短时 RSA-OAEP 密文，但 HTTP 下仍不能防中间人替换公钥/脚本；部署阶段另行确定 HTTPS、可信代理、安全 Cookie 和后台暴露策略，不能把当前开发配置直接用于公网。
- 当前虚构样本在 `samples/markdown-import/sample-note.md`，引用两张 `images/*.png`。用于本地图片导入时，将该目录的相对结构打成 ZIP；不得直接把样本发布成阿霾的真实文章。

## 计划中的启动命令（实现后）

先由站点主人提供本机专用测试库/账号，并在被 Git 忽略的本地环境文件中设置 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_USERNAME`、`MYSQL_PASSWORD` 和 `OWNER_PASSWORD`；开发脚本固定使用 `portfolio_dev` 和 `dev` 配置，不要在聊天、命令参数或仓库提交中暴露密码。随后分别启动两个应用进程：

```powershell
cd backend
.\run-local.ps1
```

```powershell
cd frontend
npm ci
npm run dev
```

预期：Nuxt 在 `http://127.0.0.1:3000` 呈现公开页；同源 `/api/v1` 到达 Spring Boot；专用测试库的 MySQL 迁移完成；初始真实项目和文章集合为空，页面使用诚实空状态而非虚构成果。前端与后端在两个目录独立构建，不能靠直接读数据库或共享进程才能运行。仓库已迁移到 `mai-portfolio`，不再使用虚拟盘符映射；前端生产构建需在新路径重新验证。

## 端到端验收顺序

1. **公开资料和空状态**：未登录打开首页、`/projects`、`/blog`；看到阿霾已确认的介绍、技术栈、GitHub 与邮箱。Resume 无文件不能下载，LinkedIn 不出现；项目和博客真实内容为空，测试卡片不出现在正式数据中。
2. **主人登录和边界**：未登录访问 `/admin` 或写 API 被拒绝；登录后能新建草稿。直接请求草稿文章、项目和媒体的公开地址得到 `404`；CSRF 缺失的写请求被拒绝。
   本地后端的 `/doc.html` 可打开 Knife4j 页面，`/v3/api-docs` 返回 OpenAPI JSON；对照实际请求核验公开与管理接口的 DTO、登录要求和错误。正式环境配置不能向匿名访客开放 Knife4j 页面、OpenAPI 数据或管理接口的交互式调试入口。
   开发启动日志应显示接口文档地址；请求/响应日志应遮盖登录密码、CSRF/Cookie 等敏感字段，且不得包含文件原始字节。`portfolio.api-log.additional-sensitive-fields` 可追加业务脱敏字段；鉴权阶段直接拒绝的请求不在 Controller AOP 范围内。
   打开 `/admin/login` 验证前端密文登录。网络请求应先获取 `/api/v1/admin/session/challenge`，随后只提交 `username`、`challengeId`、`encryptedPassword`；不得出现明文 `password` 字段。旧明文请求须返回 `422`，重放同一挑战也须拒绝。Knife4j 只描述协议，不自动加密密码。
   站长资料管理接口为 `GET/PATCH /api/v1/admin/profile`。先登录并读取资料；更新时提交昵称、定位、介绍、GitHub、邮箱的完整快照，原样带回 `updatedAt`，并携带 `X-CSRF-Token`。将 GitHub/邮箱设为 `null` 可撤销公开；旧 `updatedAt` 返回 `409`，无效邮箱/非 GitHub 个人主页返回 `422`。头像、简历和 SEO 字段不由此接口修改。
   对空标题、非法状态码、重复 slug、重复关联和不存在的文章/媒体 ID 发请求：必须先由服务端返回明确业务错误，不能向用户透出 MySQL 异常。显式清理后运行孤儿关联核对查询；数据库的 UNIQUE/FK 仅用于完整性，不替代上述校验。

   ```sql
   SELECT constraint_type, COUNT(*) AS total
   FROM information_schema.table_constraints
   WHERE constraint_schema = DATABASE()
     AND constraint_type IN ('FOREIGN KEY', 'UNIQUE', 'CHECK')
   GROUP BY constraint_type;
   ```

   核对预期外键和唯一索引存在、业务 `CHECK` 不存在；外键不启用数据库级联删除。这项检查不能代替服务层的非法输入与并发测试。
   集成测试另建立一个 `NORMAL` 类型的临时测试账号，确认它不能调用任何管理接口；首版页面仍不提供访客注册或登录入口。

   ```sql
   SELECT COUNT(*) AS primary_key_columns,
          SUM(column_name = 'id' AND extra LIKE '%auto_increment%') AS auto_increment_ids
   FROM information_schema.columns
   WHERE table_schema = DATABASE() AND column_key = 'PRI';
   ```

   首版预期两个值均为 12；再核对项目、文章、媒体、账号类型、关联角色及 0/1 开关均为 TINYINT 编码，没有 VARCHAR/BOOLEAN 枚举列；重复关联由服务层先拒绝，组合唯一索引仍有效。使用连续媒体 ID 尝试匿名读取草稿图片，必须返回 `404`。
3. **项目卡片**：在管理端新建含图片、介绍、贡献、标签和可选外链的项目，保存后公开列表不出现；发布后首页/项目列表出现；下架后列表与旧媒体地址失效。流程不上传项目源码。
4. **Markdown 导入**：把 `samples/markdown-import/` 打包为保留相对路径的 ZIP，导入后检查两张 PNG 均入库并在草稿预览显示；移除其中一张后再导入，须指出准确引用且不得发布。纯 `.md` 也可导入为草稿，Word/PDF 输入应明确拒绝。
5. **网络图片归档**：在受控测试笔记中放一张有使用权的公开网络图片，站长确认归档后，正文/导出只引用站内媒体地址，OSS 中有对象；若来源 404、超大或指向内网/回环/元数据地址，则逐项失败，文章保持草稿。浏览器不能直接回退加载原始第三方 URL。
6. **编辑与发布**：站内创建并编辑 Markdown、标签和图片，保存后重登仍在；发布后文章可被匿名阅读。版本过旧的编辑返回冲突提示，不覆盖新内容。
7. **下载授权与文件质量**：站长对草稿和发布文章分别导出 `.md`、`.docx`、`.pdf`；用含中文标题、代码块、链接和图片的样本打开文件检查语义与图片。访客只有文章发布且逐篇允许下载时才能导出；撤销许可或下架后直接请求失败。
8. **SEO/GEO 基础**：用原始 HTTP 响应（不执行浏览器脚本）检查 `/`、`/projects` 和一篇已发布 `/blog/{slug}`，正文、title、description、canonical 已存在；文章结构化数据与可见作者/日期一致；`sitemap.xml` 只列已发布地址，后台与草稿不可索引。收录和 AI 引用不作为本地验收承诺。
9. **两种媒体配置**：本地模式和 OSS 模式运行相同的媒体访问/撤稿测试。只有本地模式通过不算 OSS 要求完成；OSS 失败时不能出现文章已发布而图片未归档的半成功状态。
10. **响应与动效**：在 360/768/1440px 检查布局；键盘可完成导航与管理操作；减少动态效果下页面仍可读。普通移动网络检查首屏身份文字在规格目标内可见。

## 自动检查（实现后）

```powershell
cd backend
mvn test
```

```powershell
cd frontend
npm test
npm run build
```

测试范围须包含 [HTTP 合同](contracts/http-api.md) 的权限与错误分支、[数据模型](data-model.md) 的状态转换，以及 [规格](spec.md) 的 SC-001 至 SC-023。浏览器端到端脚本在实现阶段接入；本规划不伪造目前不存在的测试结果。

## 部署前另行决定

等本地首版可运行并完成 OSS 集成验证后，再按主要访客地域确认服务器、域名、HTTPS、备案与图片访问路径、预算、备份、告警和部署流水线。此时重新测量性能，不直接沿用本地结果。
