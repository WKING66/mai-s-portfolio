# 作品集 SEO/GEO 基础联调记录

日期：2026-10-05。功能分支 `feature/portfolio-seo`，父分支 `feature/profile-management`（`ff16138`）。不合并父分支/main。

## 实现范围

公开首页和项目栏目保留现有 SSR 内容与 A 版布局；加入基于真实公开数据的 title/description、Open Graph、canonical、ProfilePage/Person 与 ItemList/CreativeWork。共用工具不读取请求 Host；项目分页各自规范到自身，越界空页 noindex。不虚构资历、日期、图片、站内项目详情或尚未实现的博客。

新增 GET `/robots.txt`、GET `/sitemap.xml`；私有 HTML 路由新增 `X-Robots-Tag: noindex,nofollow`。域名通过 `NUXT_PUBLIC_SITE_URL` 运行时提供：默认空值明确禁止索引，sitemap 503；有效 HTTP(S) origin 才启用公开元数据。未确定真实部署域名，本轮使用保留测试域名 `https://portfolio.test`，不写入环境文件或正式配置。

## 真实环境及页面操作

同一份 Node 24.19.0 生产构建分别运行于 3004（测试 origin）和 3006（空 origin），都代理既有真实后端 19334。使用独立 `portfolio_dev.project_acceptance_20261005` schema，公开四项目，`project_owner` 夹具账号；不修改正式库、OSS 或 Docker。浏览器独立会话 `seo-acceptance-20261005`。

|操作|参数/请求|状态及实际结果|
|---|---|---|
|地址栏打开 3004 首页|GET `/`；SSR 内部读取公开资料和 PUBLIC/page=1/size=4|200；标题来自当前昵称/定位，介绍来自公开资料；canonical=`https://portfolio.test/`，robots=index,follow，ProfilePage/Person 名称和介绍与 API 一致；没有私有编辑器或管理缓存|
|首页点击主导航“项目”|链接 `/#projects`|仍在首页，hash=#projects；四张卡片，区域顶部距视口 69.53px；未破坏原导航行为|
|地址栏打开项目栏目|`/projects?page=1&ignored=1`；PUBLIC/page=1/size=12|200；四张卡片；canonical 去掉 page=1/ignored；ItemList 顺序、位置和外链来自同一 PUBLIC 响应，未添加隐藏链接或草稿|
|读取第二页原始 HTML|`/projects?page=2&ignored=1`；PUBLIC/page=2/size=12|200；真实库仅四个公开项目，此页为空；canonical=`https://portfolio.test/projects?page=2`，noindex，无虚构 ItemList。合法非空分页位置另外由单元测试验证；本轮未为 SEO 测试发布其他草稿|
|登录站长|登录页输入 `project_owner` 与夹具密码（脱敏），点击“登录”，回到 `/admin/profile`|POST/GET session 200，管理资料 GET 200；浏览器挂载编辑器，但携带 Cookie 读取管理 SSR HTML 不含编辑器或验收资料，仍 noindex|
|站长读取地图|GET `/sitemap.xml`|200/application+xml；仅首页和项目栏目两个真实地址，不含草稿、管理、假详情、博客、假 lastmod|
|脚本注入文本检查|资料页介绍输入 `SEO 安全验收 </script><script>window.__seoProbe=true</script>`，点击保存；点击“查看公开首页”，再完整重载首页|PATCH profile 200；JSON-LD 能解析且 `<` 编为 `\u003C`，正文为转义文本；完整 SSR 重载后 probeExecuted=false、额外探针脚本数=0|
|恢复介绍|返回资料页，将介绍恢复为此前“资料管理联调…”完整文本，点击保存|PATCH 200，成功提示；随后公开 HTML 确认原验收介绍恢复。不是恢复正式库或全部夹具原始资料|
|注销并查看公开页|管理导航点击“注销”，再次打开首页|DELETE session 200；匿名标题、canonical、公开 JSON-LD 与站长公开视图一致，地图仍只列上述两项|
|地址栏打开 robots|3004 `/robots.txt`|200/text/plain；Allow:/、禁止 admin API 抓取、声明测试 origin 的 sitemap；管理 HTML 壳仍可读取 noindex|
|地址栏打开 sitemap|3004 `/sitemap.xml`|200；浏览器 XML 树显示首页和项目栏目|
|地址栏打开空配置首页|3006 `/`|200；正文正常，robots=noindex,nofollow；无 canonical、og:url 或 JSON-LD，不以 localhost 伪造正式域名|
|地址栏打开空配置 sitemap|3006 `/sitemap.xml`|503/text/plain，浏览器显示 `Site URL is not configured`；robots 200 且全站 Disallow:/|
|响应头与 Host 污染负向检查|真实 HTTP 读取 login/forbidden/admin/profile/admin/projects；sitemap 请求注入测试 Host/X-Forwarded-Host|四个 HTML 响应均 200，头与 meta 均 noindex；管理 HTML 无表单。污染 Host 不改变 sitemap 状态或字节内容|

注入文本的初次粗略字符串检查在 meta 属性中找到字面 `<script>`；这不是脚本执行证据。进一步检查确认：文本处于带引号的 description 属性，JSON-LD、正文及 Nuxt payload 分别安全转义，完整重载没有执行。最终介绍恢复并注销，独立浏览器会话已关闭。

## 可重复检查与结果

- Node 24 下最终 Vitest **70/70**（七文件）通过，含 SEO 22 项；最终类型检查与生产构建通过，git diff --check 通过。
- `frontend/app/test/seo.integration.mjs` 使用 Node 原生测试及真实 HTTP，无依赖、无服务启动、无数据库写入。测试 origin 与空 origin 各 **5/5** 通过，共十项；覆盖 SSR、真实公开数据、分页、robots/sitemap、配置缺失、Host 污染、隐私响应头。
- 在 frontend 目录对已运行环境执行；参数必须与该预览运行配置一致：

```powershell
$env:SEO_BASE_URL='http://127.0.0.1:3004'
$env:SEO_EXPECTED_ORIGIN='https://portfolio.test'
node --test app/test/seo.integration.mjs
```

- 构建中发现 Nuxt/Nitro 将 shared 工具相对导入错误外置到生成目录；五处应用/Nitro 导入改为 Nuxt `#shared` 别名后真实生产构建通过。未修改生成物、升级依赖或绕过失败。
- 独立只读复核覆盖 SEO 实现、测试及 README 配置说明；未发现可确认缺陷。后端未改动，不重复 Maven 全量回归。

## 副作用与未完成项

仅在隔离资料表保存并恢复介绍，时间戳推进两次；创建/注销隔离会话。未改项目状态或存储对象。域名测试不等同真实上线、爬虫收录、排名、社交分享抓取或 AI 引用验收。博客接口/页面落地后还须扩充真实已发布文章和对应结构化数据/站点地图；本轮不宣称首版整体完成。维护者后端 dev 配置不纳入提交。
