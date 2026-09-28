# 个人作品集网站交接（2026-09-25）

## 当前结论

- 视觉方向暂定为 A「蓝调档案」，先不要继续大幅调整风格，除非用户提出新反馈。参考：[Wilson Costa 作品集](https://wilsoncosta-portfolio.vercel.app/#certifications)；借鉴布局和交互语言，不逐像素复制。
- 网站面向招聘者与潜在合作方，二者同等重要；用项目、博客和具体贡献展示学生的优点，不主动强调没有实习经历。
- 气质为冷静、专业、可信，辅以蓝色光晕、半透明卡片和适度动效。提供浅色/深色主题（默认跟随设备）与减少动态效果版本。
- 需求规格、静态视觉原型及首版实施规划已完成；**尚无可发布的网站功能**。技术方案为独立 Nuxt SSR 前端与 Spring Boot 后端、MySQL 8.4、阿里云私有 OSS。后端本地提供 Swagger/OpenAPI 接口文档；正式环境默认关闭交互式文档。服务器、域名、备案和正式部署待本地首版跑通后选择。
- 博客首版仅导入 Markdown 笔记，保留后续 Word/PDF 等格式的接入接口；纯 `.md` 可直接导入，带本地图片的笔记以 ZIP 保留相对目录树作为传输包装（不新增文章格式）。本地及网络图片需受控归档到 OSS，缺失或失败须明确提示，且不自动发布。站内仅编辑 Markdown，网页阅读页按 Markdown 语义渲染。文章至少导出 Markdown、Word（.docx）和 PDF。站点主人可导出自己的草稿及已发布文章；访客只能导出已发布且按篇明确允许下载的文章，许可默认关闭。
- 项目由站点主人在网站管理端新增、编辑、保存、发布和下架；只维护介绍、贡献、标签、展示图片及可选的代码/演示入口，不上传源码或整个项目文件夹。首版在首页和项目列表用展示卡片呈现，不做站内独立项目详情页；访客只能看到已发布的项目。
- 已确认公开资料见 `content/confirmed-content.md`：昵称阿霾、Java 后端向 AI 智能体开发转型的介绍、GitHub `WKING66` 与邮箱；Resume 暂无文件，LinkedIn 不公开。技术栈清单已提供。真实项目和博客文章尚未提供。
- 首版不实现 AI Agent、Redis/Caffeine 或向量检索；项目与文章保留稳定标识、状态和更新时间，第二版 Agent 只能引用仍然公开的内容。SEO/GEO 首版落实在 SSR 正文、元数据、结构化数据、站点地图及原创内容，不能承诺收录或 AI 引用。
- 首版表设计以 `specs/001-personal-portfolio/data-model.md` 确认稿为准：12 张表均用自增数值 `id` 主键（关联表也一样）；所有状态、类别、角色及 0/1 开关统一用 TINYINT 编码。`user_account.type` 区分站主与普通账号，首版仅开放站主登录、不开放访客注册/登录。**所有业务校验先在服务端完成**；数据库允许唯一索引和物理外键维护完整性，但不依赖数据库异常做正常校验，不使用级联删除。

## 文件与预览

| 内容 | 位置 |
| --- | --- |
| 完整需求与验收标准 | `specs/001-personal-portfolio/spec.md` |
| 需求质量清单 | `specs/001-personal-portfolio/checklists/requirements.md` |
| 技术计划与取舍 | `specs/001-personal-portfolio/plan.md`、`research.md` |
| 数据、接口与本地验收 | `specs/001-personal-portfolio/data-model.md`、`contracts/http-api.md`、`quickstart.md` |
| 数据模型评审与升级参考 | `specs/001-personal-portfolio/data-model-review.md`、`data-model-upgrade.md` |
| 三版入口 | `prototypes/index.html` |
| 暂定方向 A | `prototypes/concept-a.html` |
| 备选 B / C | `prototypes/concept-b.html`、`prototypes/concept-c.html` |
| 共用样式与脚本 | `prototypes/base.css`、`prototypes/base.js` |
| A 版最小检查 | `prototypes/check-a.cjs` |
| 已确认公开内容 | `content/confirmed-content.md` |
| Markdown 图片导入测试素材 | `samples/markdown-import/sample-note.md` 与同目录 `images/` |

本地预览若未运行，在仓库根目录执行 `python -m http.server 4173 --directory prototypes`，然后打开 `http://127.0.0.1:4173/concept-a.html`。若 4173 端口已被现有预览占用，不要重复启动。检查命令：`node prototypes/check-a.cjs`。

## A 版已确定的视觉细节

- 首页首屏现使用已确认昵称和介绍；其下保留 Resume（无文件、不可下载）、GitHub 和 Email 入口。LinkedIn 未获公开批准，不显示。头像仍为占位。
- 技术栈为四个分类大卡片；项目名称已按用户提供清单替换，Logo 横向连续移动、相邻行反向移动；**单个 Logo 无独立边框或底色**。常见图标来自 Devicon，LangChain/LangGraph 等使用其他图标源；MyBatis-Plus、pgvector 暂用文字标记，正式版须核对。
- 卡片有缓慢流光、鼠标跟随光斑与倾斜反馈；触控设备不启用指针倾斜，系统设置“减少动态效果”时停止持续动画。
- 项目、博客和学习成果仍用明确标记的测试占位，不是用户真实资料；页尾邮箱与 GitHub 已按批准资料接通。
- 早期视觉验证覆盖轨道移动、卡片倾斜与光斑、窄屏和无边框 Logo。替换技术清单后只重新运行了 `node prototypes/check-a.cjs`（通过），新增图标的浏览器加载状态尚未逐项复核；用户已要求停止继续打磨静态页面。

## 后续需要用户提供或确认

1. 真实项目资料：基本介绍、本人贡献、技术标签、成果、截图、代码/演示链接及公开许可；目前项目数据留空，测试卡片不得当成真实作品。
2. 愿意公开的 Markdown 笔记与博客主题；已有含两张本地 PNG 的虚构测试笔记，仍需确认真实笔记、附件、内部链接和导入后的人工校对流程。
3. 头像与简历文件、学校/课程/证书等真实资料；未提供前维持空状态。
4. 实施阶段需提供 OSS 测试 Bucket 与凭据（不得入库）；正式服务器、域名、预算和备案待本地首版通过验收后讨论。用户已要求停止继续打磨静态网页。

## 接续工作建议

下一步可运行 `$speckit-tasks` 将 `plan.md` 拆成依赖有序的实现任务，再按 `quickstart.md` 完成本地纵向流程。真实项目与文章之后由站点主人通过管理端添加；不要把当前静态原型或测试素材误认为已完成的网站功能。

需特别核对规格 `FR-024` 与当前原型的信息顺序：规格强调首屏后项目/博客的视觉权重，A 版目前先出现技术栈；正式开发前请与用户确认是否调整顺序。规格同时把“可下载简历”列为待定范围，而原型仅有 Resume 占位入口。

不要擅自清理或覆盖仓库中已有的规划、静态原型和用户文件。
