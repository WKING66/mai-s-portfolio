# 当前接口审查修复与真实前后端联调验收

- 日期：2026-10-04（Asia/Shanghai）
- 功能分支：`feature/fix-api-review-acceptance`
- 父分支：`feature/auth-fixed-rsa`，起点 `38a24eb`
- 范围：上轮已确认的四项审查问题；没有新增项目/博客/Agent 业务功能，没有合并父分支。
- 最终依据：实际浏览器界面操作及真实 HTTP 返回；单元测试只作为补充回归保障。
- 下文是实际执行记录，不是尚未执行的测试计划。密码、Cookie、CSRF、RSA 密文及私钥均不写入文档或证据文件。

## 1. 实际修复

| 问题 | 修改 | 实际联调依据 |
| --- | --- | --- |
| 成功普通用户登录可清空整个 IP 的失败尝试窗口 | 删除业务的成功登录重置入口；成功、失败均占用同一 Redis 固定窗口，直到 TTL 到期 | AUTH-06：401/200/401/200/401/429；AUTH-07：自然到期后恢复登录 |
| 头像、简历公开撤回后仍可能从 300 秒缓存读取 | 公开媒体成功响应改为 Cache-Control: no-store，保留流式读取及关闭资源 | MEDIA-01、MEDIA-05：先200，再撤回引用，同一浏览器同URL404 |
| 非法路径参数、错误请求方法误报500 | Web统一异常处理新增400、405，以及相邻的415处理；405保留Allow，415保留Accept；提示进入常量 | MEDIA-03/04实际400/405及Allow=GET；415另由MVC回归测试覆盖 |
| Nuxt内置请求遇到非2xx直接抛异常，业务提示无法显示 | 统一请求函数读取FetchError.data中的R响应，非统一响应使用安全提示；关闭自动重试 | 实际登录401显示“用户名或密码错误”，429显示“登录尝试过于频繁，请稍后重试” |

既有能力保留：固定 RSA-OAEP 前端加密、Argon2 服务端密码校验、统一普通用户/站长会话、HttpOnly Cookie、Origin检查、写接口CSRF、Sa-Token OWNER授权、PostgreSQL乐观更新、真实OSS流式访问、`@ApiLog`敏感字段过滤。不引入新认证协议。

## 2. 真实验收环境与隔离

| 项目 | 实际使用 |
| --- | --- |
| Nuxt界面/同源代理 | http://127.0.0.1:3002/，登录 http://127.0.0.1:3002/login |
| Spring Boot接口/Knife4j | http://127.0.0.1:19333/doc.html；OpenAPI /v3/api-docs |
| PostgreSQL | 本机5432，portfolio_dev，独立schema api_acceptance_20261004 |
| Redis | 本机6379，独立键前缀 mai-portfolio:acceptance:20261004 |
| OSS | backend/.env指定的真实amai-portfolio bucket；没有内存或本地存储替代 |
| 登录限制 | 同一IP 5分钟最多5次，与当前开发默认值一致 |
| 验收账号 | OWNER：owner；NORMAL：acceptance_normal；均为独立schema夹具账号，不使用真实站长密码 |

原服务9333和3000未停止或改配置，未操作Docker、未创建盘符映射。测试服务使用dev配置，仅以进程参数覆盖端口、schema、Redis前缀及允许Origin；两个数据源URL均覆盖（包括Druid嵌套URL）。夹具已执行V1结构，测试schema启动不再次执行Flyway，也不运行生产账号初始化。

本轮创建独立schema，复制公开资料文本及21项技术标签；只在该schema新增账号、媒体记录并修改site_config。夹具密码保存在Git忽略的 `.local-backups/acceptance-20261004.env`，不要提交该文件。测试账号密码为随机生成，与真实账号密码无关。

新增OSS对象，全部保留，未删除或覆盖已有对象：

- `acceptance/2026-10-04/d8eafd02-a1ff-4640-8715-ca4aad02da93/avatar.png`
- 同前缀 `resume.pdf`（最初的最小PDF夹具，随后改用下面的新对象）
- 同前缀 `private.png`
- `acceptance/2026-10-04/388861b3-7b45-45cf-bed5-79c74f33da4b/resume.pdf`（有效单页602字节验收PDF）

媒体id1：1像素PNG头像夹具；id2：PDF简历夹具；id3：READY但未被公开资料引用的图片。仅用于链路验证，不作为真实头像、简历或作品数据。

## 3. 全部已实现HTTP功能接口

实际 /v3/api-docs 枚举共7个操作；Knife4j也显示GET4、POST1、DELETE1、PATCH1。项目/博客页面目前存在展示占位，但没有已实现的项目/博客管理HTTP接口，本轮不虚构这些功能验收。

| 接口 | 使用的真实界面 | 主链路及主要负向用例 |
| --- | --- | --- |
| GET /api/v1/auth/session | Nuxt登录页打开、刷新、注销后的状态 | 匿名false；登录刷新仍true；注销后false |
| POST /api/v1/auth/session | Nuxt“用户名/密码/登录” | NORMAL、OWNER成功；错误密码401；固定窗口429及自然到期恢复 |
| DELETE /api/v1/auth/session | Nuxt“注销” | Cookie+CSRF注销200，返回未登录表单 |
| GET /api/v1/admin/profile | Knife4j“站长资料→读取站长资料→调试→发送” | OWNER200、NORMAL403、匿名401 |
| PATCH /api/v1/admin/profile | Knife4j“更新站长公开资料→调试→raw JSON/请求头部→发送”；随后Nuxt首页 | 保存200并首页生效；旧版本409；CSRF403；邮箱/GitHub422；JSON400 |
| GET /api/v1/public/profile | Nuxt首页SSR及后续重新打开 | 资料/联系入口/技术栈与真实数据库一致；修改后展示更新；撤回媒体后URL为空 |
| GET /api/v1/public/profile/media/{assetId} | 首页头像及Resume链接；Knife4j媒体调试 | 真实OSS图片/PDF200；私有404；非法id400；错误方法405；撤回后404 |

目前没有站长资料编辑页面或媒体公开撤回页面：资料编辑使用已有Knife4j界面真实发送；媒体撤回使用明确标注的测试夹具SQL，仅改测试schema。这两者不得描述成产品已有后台编辑/撤回按钮。

## 4. 逐步操作、输入与实际结果

密码输入以本地夹具键名代替实际值；请求体中的 encryptedPassword 由真正的前端生成。CSRF通过会话取得，填写Knife4j X-CSRF-Token请求头时只在浏览器内使用，不输出值。Knife4j按钮实际显示“发 送”，下文统一称“发送”。

### AUTH-01 · GET /api/v1/auth/session

- 界面：http://127.0.0.1:3002/login
- 状态：通过

操作：

1. 打开登录页面，等待自动会话查询完成。

输入：

无；不携带测试账号会话

实际结果：显示用户名、密码输入框和“登录”按钮；浏览器捕获 GET /api/v1/auth/session HTTP 200（19:40:19），不创建登录 Cookie。

### AUTH-02 · POST /api/v1/auth/session

- 界面：http://127.0.0.1:3002/login
- 状态：通过

操作：

1. 在“用户名”输入 owner。
2. 在“密码”输入 incorrect-20261004。
3. 点击“登录”。

输入：

```json
{
  "username": "owner",
  "password": "incorrect-20261004（故意错误，仅前端内存中）"
}
```

实际结果：重新配对验收公钥后，点击登录返回 HTTP 401；页面显示“用户名或密码错误”，无技术请求错误。

### PUBLIC-01 · GET /api/v1/public/profile

- 界面：http://127.0.0.1:3002/
- 状态：通过

操作：

1. 打开首页；等待 Nuxt SSR 与公开资料加载。
2. 浏览器页内读取同源接口确认数据源隔离。

输入：

无

实际结果：HTTP 200 / OK；昵称阿霾、37 个缺陷介绍、GitHub、Email、21 项技术；avatarUrl=/api/v1/public/profile/media/1，resumeUrl=/api/v1/public/profile/media/2

### AUTH-03 · POST /api/v1/auth/session

- 界面：http://127.0.0.1:3002/login
- 状态：通过

操作：

1. 用户名输入 acceptance_normal。
2. 密码使用 .local-backups/acceptance-20261004.env 的 NORMAL_PASSWORD（独立验收账号，值不记录）。
3. 点击“登录”。
4. 刷新页面，等待会话查询。

输入：

username=acceptance_normal；encryptedPassword=前端配对公钥生成的 RSA-OAEP 密文（不记录值）

实际结果：登录 HTTP 200；页面显示“已登录 / acceptance_normal，你已登录。可访问的功能由账号权限决定。”；刷新依然显示已登录。

### AUTH-04 · DELETE /api/v1/auth/session

- 界面：http://127.0.0.1:3002/login
- 状态：通过

操作：

1. 普通用户已登录时，点击“注销”。

输入：

前端自动发送 Cookie 与 X-CSRF-Token，不记录敏感值。

实际结果：HTTP 200；已登录提示消失，恢复用户名、密码输入框及登录按钮。

### ADMIN-01 · GET /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 先在前端以 acceptance_normal 登录。
2. Knife4j 展开“站长资料”，点击“读取站长资料”，点击“调试”，点击“发送”。

输入：

无；普通用户会话

实际结果：HTTP 403，code=FORBIDDEN，message=权限不足。

### ADMIN-02 · GET /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 在前端点击“注销”后，打开 Knife4j 的“站长资料 → 读取站长资料 → 调试”。
2. 点击“发送”。

输入：

无；无登录会话

实际结果：HTTP 401，code=UNAUTHENTICATED，message=请先登录。

### AUTH-05 · POST /api/v1/auth/session

- 界面：http://127.0.0.1:3002/login
- 状态：通过

操作：

1. 用户名 owner，密码使用独立夹具 OWNER_PASSWORD（不记录值）。
2. 点击“登录”。

输入：

owner＋独立验收密码；前端实际 RSA-OAEP 加密。

实际结果：HTTP 200；页面显示“已登录 / owner，你已登录。可访问的功能由账号权限决定。”

### ADMIN-03 · GET /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 前端 owner 登录成功后，Knife4j“站长资料 → 读取站长资料 → 调试 → 发送”。

输入：

OWNER 会话

实际结果：HTTP 200 / OK；昵称阿霾，updatedAt=2026-09-26T07:34:59.85；头像1、简历2。

### PROFILE-01 · PATCH /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 站长登录后选择“更新站长公开资料 → 调试”。
2. 请求头部填写当前会话 X-CSRF-Token（值不记录）。
3. raw JSON 编辑器填写完整验收资料；点击“发送”。

输入：

```json
{
  "displayName": "阿霾 · 联调验收",
  "headline": "Java 后端 / AI 智能体开发",
  "intro": "联调验收：项目、博客与工程实践；不修改真实公开资料。",
  "githubUrl": "https://github.com/WKING66",
  "email": "2899964923@qq.com",
  "updatedAt": "2026-09-26T07:34:59.85"
}
```

实际结果：浏览器实际 PATCH HTTP 200 / OK；返回验收昵称和更新时间 2026-10-04T12:00:51.263；仅独立 schema 更新。

### PROFILE-02 · PATCH /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 成功保存后，不更新请求 updatedAt，原样再次点击“发送”。

输入：

PROFILE-01 相同 JSON，旧 updatedAt

实际结果：HTTP 409 / DATA_CONFLICT，数据状态已变化，请刷新后重试。

### PROFILE-03 · PATCH /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 请求头部 X-CSRF-Token 输入 invalid-csrf-acceptance。
2. 保留完整 JSON，点击“发送”。

输入：

错误 CSRF 字符串；其他 JSON 同 PROFILE-01

实际结果：HTTP 403；服务端 CSRF_INVALID：缺少有效的写入令牌；没有更新资料。

### PROFILE-04 · PATCH /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 保留成功 JSON，将 updatedAt 改为2026-10-04T12:00:51.263，email 改为 not-an-email。
2. 点击“发送”。

输入：

其余字段同 PROFILE-01

实际结果：HTTP 422；VALIDATION_FAILED / 请求参数不合法；details=[email: 邮箱格式不正确]；未保存。

### PROFILE-05 · PATCH /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 恢复正确邮箱，GitHub 改为 https://evil.example/WKING66。
2. 点击“发送”。

输入：

updatedAt=2026-10-04T12:00:51.263

实际结果：HTTP 422；VALIDATION_FAILED / GitHub 地址必须是有效的 github.com 个人主页链接；未保存。

### PROFILE-06 · PATCH /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. raw JSON 编辑器改为不完整 JSON：{"displayName":
2. 点击“发送”。

输入：

{"displayName":

实际结果：HTTP 400；MALFORMED_REQUEST / 请求体格式不正确；未保存。

### PUBLIC-02 · GET /api/v1/public/profile

- 界面：http://127.0.0.1:3002/
- 状态：通过

操作：

1. 站长保存 PROFILE-01 后打开 Nuxt 首页。

输入：

无

实际结果：实际页面标题昵称为“阿霾 · 联调验收”，定位、介绍与 PATCH 保存一致。SSR→后端→PostgreSQL 链路通过。

### MEDIA-01 · GET /api/v1/public/profile/media/{assetId}

- 界面：http://127.0.0.1:3002/
- 状态：通过

操作：

1. 打开首页，确认头像实际加载。
2. 点击介绍下方 Resume。
3. 通过浏览器响应补充核对类型、文件字节和缓存头。

输入：

头像 assetId=1；简历 assetId=2；不要求登录。

实际结果：头像 HTTP200 / image/png，naturalWidth=1；简历200 / application/pdf，602字节有效单页PDF，签名%PDF-1.4，Content-Disposition=attachment; filename=resume.pdf；两者 Cache-Control=no-store；点击Resume发生真实文件请求。

### MEDIA-02 · GET /api/v1/public/profile/media/{assetId}

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 公开个人媒体 → 读取公开个人媒体 → 调试。
2. 请求参数 assetId 填3，点击“发送”。

输入：

assetId=3；已 READY 但没有被站长资料引用的真实 OSS 图片。

实际结果：HTTP 404；NOT_FOUND / 公开媒体不存在；没有公开私有资源。

### MEDIA-03 · GET /api/v1/public/profile/media/{assetId}

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 将 assetId 改为 not-a-number，点击“发送”。

输入：

assetId=not-a-number

实际结果：HTTP 400 / MALFORMED_REQUEST / 请求参数格式不正确；不再 HTTP500，也不回显非法参数或异常堆栈。

### MEDIA-04 · POST /api/v1/public/profile/media/1（负向方法测试）

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. assetId 恢复1。
2. 请求方法下拉框 GET 改为 POST，点击“发送”。
3. 响应区域点击 Headers。

输入：

POST /api/v1/public/profile/media/1

实际结果：HTTP405 / METHOD_NOT_ALLOWED / 请求方法不支持；Headers 显示 allow=GET；不再误报500。

### AUTH-06 · POST /api/v1/auth/session

- 界面：http://127.0.0.1:3002/login
- 状态：通过

操作：

1. 在已结束的旧限流窗口之后，匿名打开登录页。
2. 1：owner / incorrect-20261004，点击登录。
3. 2：acceptance_normal / NORMAL_PASSWORD，点击登录，成功后点击注销。
4. 3：owner / incorrect-20261004，点击登录。
5. 4：acceptance_normal / NORMAL_PASSWORD，点击登录，成功后点击注销。
6. 5：owner / incorrect-20261004，点击登录。
7. 6：acceptance_normal / NORMAL_PASSWORD，点击登录。

输入：

同一浏览器/IP；服务端窗口5分钟、最多5次；密码与密文不记录。

实际结果：实际 POST 顺序 401、200、401、200、401、429；两次成功普通用户登录未清除累计次数；第六次即使凭据正确也返回429，前端提示“登录尝试过于频繁，请稍后重试”，没有技术错误且未自动重试。

### MEDIA-05 · GET /api/v1/public/profile/media/{assetId}

- 界面：http://127.0.0.1:3002/
- 状态：通过

操作：

1. MEDIA-01 已在同一浏览器成功打开头像1和简历2。
2. 测试夹具仅将 api_acceptance_20261004.site_config 的 avatar_media_id、resume_media_id 设置NULL；当前无管理媒体撤回界面，不虚构按钮。
3. 重新打开首页。
4. 在浏览器复访刚访问过的 /api/v1/public/profile/media/1 和 /2；另在地址栏打开 /1。

输入：

保持 OSS 文件及 media_asset READY 不变，仅撤销公开引用。

实际结果：首页不再显示头像，Resume 不再是可点击链接；公开资料 avatarUrl/resumeUrl=null；相同浏览器同一URL重新访问均404 / NOT_FOUND / 公开媒体不存在，不返回此前200的文件缓存。

### AUTH-07 · POST /api/v1/auth/session

- 界面：http://127.0.0.1:3002/login
- 状态：通过

操作：

1. AUTH-06 的五分钟窗口自然到期后，输入 owner 与独立验收 OWNER_PASSWORD，点击“登录”。

输入：

没有人工删除或重置 Redis 限流键。

实际结果：HTTP200，页面显示 owner 已登录；TTL 到期后的放行恢复正常。

### PROFILE-07 · PATCH /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 重新打开修改接口的当前可见调试页。
2. raw JSON 请求编辑器填入原昵称、定位、介绍、GitHub、Email，updatedAt=2026-10-04T12:00:51.263。
3. 请求头使用当前站长 CSRF，点击“发送”。

输入：

完整原始公开文本；头像/简历仍保持撤销，不由这个接口改动。

实际结果：HTTP200 / OK；昵称恢复阿霾，updatedAt=2026-10-04T12:19:09.819；头像、简历为空。

### PROFILE-08 · PATCH /api/v1/admin/profile

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 前端注销站长后，以 acceptance_normal 与 NORMAL_PASSWORD 点击登录。
2. 在更新站长公开资料调试页保留完整JSON，把 X-CSRF-Token 改为当前普通用户会话令牌。
3. 点击“发送”。

输入：

NORMAL会话、有效CSRF；完整资料JSON

实际结果：实际PATCH返回403 / FORBIDDEN / 权限不足；没有修改资料。

### AUTH-08 · DELETE /api/v1/auth/session

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. 普通用户仍已登录时，用户会话 → 用户登出 → 调试 → 请求头部。
2. X-CSRF-Token 输入 invalid-csrf-acceptance，点击“发送”。
3. 返回 Nuxt 登录页，确认依旧显示 acceptance_normal 已登录。
4. 点击 Nuxt“注销”，确认恢复未登录表单。

输入：

错误CSRF用于Knife4j；前端按钮随后使用正确会话CSRF。

实际结果：错误CSRF实际DELETE403 / CSRF_INVALID / 缺少有效的写入令牌，不会注销会话；正常前端注销200，会话清除。

### AUTH-09 · DELETE /api/v1/auth/session

- 界面：http://127.0.0.1:19333/doc.html
- 状态：通过

操作：

1. Nuxt 正常注销后，再打开用户登出调试页。
2. 保留非空请求头，点击“发送”。

输入：

无登录Cookie；invalid-csrf-acceptance

实际结果：实际DELETE401 / UNAUTHENTICATED / 请先登录。

### MEDIA-06 · GET /api/v1/public/profile（恢复终检）

- 界面：http://127.0.0.1:3002/
- 状态：通过

操作：

1. 测试夹具将独立schema的头像/简历引用恢复为1、2；不修改真实public schema、不删除文件。
2. 重新打开Nuxt首页，确认昵称、介绍和Resume链接恢复。
3. 补充读取资料/会话字段与头像加载状态。

输入：

测试头像1、简历2；当前无登录会话。

实际结果：页面显示原昵称阿霾与原介绍、GitHub/Email、Resume可点击；公开接口URL为media/1和media/2，技术栈21项，头像已加载，loggedIn=false。只读数据库对比：public.updatedAt仍为2026-09-26 07:34:59.850；验收资料updatedAt=2026-10-04 12:19:09.819。

## 5. 证据与不能混淆的补充检查

- 浏览器网络记录取自本轮独立会话，实际点击登录、注销、Knife4j发送、Resume后捕获HTTP方法/状态/URL/时间；另存已脱敏CSV，不包含请求头或请求体秘密。
- 页面读取：实际Nuxt昵称、定位、介绍、联系入口；错误密码/限流的中文提示；刷新已登录状态；注销后的表单。
- 补充的浏览器页内fetch仅用于核对文件响应头/字节、会话字段和准备当前updatedAt/CSRF，不替代上述按钮触发的请求。媒体撤回后复访同URL含补充fetch检查。
- 已登录会话可取得CSRF，JavaScript不可读取HttpOnly会话Cookie；注销后loggedIn=false且无CSRF。这里不记录任何令牌值。
- 简历点击确实产生Document请求200，Content-Type=application/pdf，Content-Disposition=attachment；检查602字节及%PDF-1.4签名。没有把夹具PDF当成真实简历排版验收。
- “缺少CSRF”的第一次Knife4j发送被其必填项校验挡住，没有HTTP请求；因此不宣称该次证明了后端403。随后填写非空错误令牌，真实请求返回403/CSRF_INVALID。
- 恢复资料时第一次自动化定位误选文档示例/隐藏标签，实际400没有写入；重新定位当前可见调试页的真正raw请求编辑器后重新执行，记录成功结果。失败不算通过。

## 6. 环境失败、修正及复测

1. 初次尝试了原3000界面，GET会话502；最终验收切换独立3002/19333，不把原服务的失败隐藏成成功。
2. 第一份独立后端启动参数只覆盖spring.datasource.url，Druid仍使用自己的URL；预检公开资料发现未进入验收schema。停止本轮19333进程、同时覆盖两个URL后重新启动；该错误期间仅执行只读公开资料请求，未修改真实资料。
3. 前端现有固定公钥与后端私钥未配对，错误密码试验先返回400“登录加密凭据格式无效”。从当前受保护私钥导出对应公开SPKI，仅注入验收Nuxt进程环境；没有输出私钥，也没有改用户.env。重新通过真正前端登录，错误密码401、正确密码200。日常运行需要确保NUXT_PUBLIC_AUTH_RSA_PUBLIC_KEY与后端AUTH_RSA_PRIVATE_KEY_LOCATION配对。
4. 初次后端验证在沙箱内因JUnit临时目录清理AccessDeniedException失败；正常本地权限重跑全量验证通过。没有降低测试标准或删除失败用例。

## 7. 测试与构建（补充，不代替联调）

- 最终后端 `mvn -B -o -f backend/pom.xml verify`：31模块BUILD SUCCESS；81条测试、80通过、0失败、0错误、1跳过。2026-10-04 20:15:26完成。
- 跳过的是显式选择启用的OSS集成测试（包含删除其测试对象），没有为跑这条测试删除bucket文件。真实OSS新增/读取已通过本轮浏览器验收，不能把跳过写成单元测试通过。
- 真实Redis集成与多实例认证回归已启用；保留Redis 3.2兼容路径，验证固定窗口及多实例共享。
- 前端Vitest12条通过，Nuxt类型检查通过，生产构建完成（Nuxt4.5.2 / Node24）。
- 新增MVC回归覆盖非法路径400、错误方法405及Allow、媒体类型415及Accept；媒体回归覆盖no-store、流式字节及关闭输入流；请求回归使用真实ofetch非2xx拒绝行为而非只模拟200失败包。
- 非阻塞警告：本机Maven settings存在既有未识别profile标签；Nuxt构建出现Vue依赖导出映射弃用提示。未顺手修改用户本机工具配置或无关依赖。

## 8. 风险、范围边界

- 同IP窗口有意统计成功与失败，会限制共享公网IP下多个合法账号的短时间频繁登录。这是阻断本轮绕过所需的固定窗口语义，不是按账号精细化策略；本轮未新增策略。
- no-store只对新响应生效，不能抹掉修复上线之前已经保存的旧缓存副本；撤回也无法回收别人已经下载的文件。
- 本轮是本地HTTP验收，沿用已批准的固定RSA方案。公网HTTPS、反向代理来源信任、多节点滚动发布和高并发压力未在本轮浏览器验收，不能据此声明达到公网安全上线条件。
- 415有实际MVC回归，但没有把它写成浏览器按钮实测；Redis多实例由真实集成测试覆盖，浏览器使用单个独立验收后端。
- 未新增/验收注册、博客导出、Word/PDF导入、项目编辑、Agent功能；未新增管理编辑页面。
- 自动化HTTP/页面断言通过不代替维护者对视觉、交互和业务边界的人工审核。

## 9. 定向阅读清单

生产实现：

- `frontend/app/api/request.ts`（Nuxt统一业务异常处理/禁止自动重试）
- `frontend/app/api/session.ts`（登录、查询、注销调用）
- System `service/LoginThrottleService.java`、`service/impl/LoginThrottleServiceImpl.java`、`service/impl/AuthServiceImpl.java`
- Portfolio `controller/PublicProfileMediaController.java`
- Web Starter `advice/GlobalExceptionHandler.java`、`exception/ApiErrorCode.java`、`WebMessageConstants.java`

回归与真实环境夹具：

- `frontend/app/api/request.test.ts`、`session.test.ts`
- System `service/impl/LoginThrottleServiceImplTest.java`
- Portfolio `controller/PublicProfileMediaControllerTest.java`
- Web Starter `advice/GlobalExceptionHandlerTest.java`
- Launch `RedisAuthFlowTest.java`
- `backend/mai-portfolio-launch/src/test/java/dev/amai/portfolio/acceptance/AcceptanceFixtures.java`：只在测试源码中执行，固定验收schema，OSS只新增不删。参数export-public-key、revoke-media、restore-media、replace-resume、inspect是测试夹具操作，不是产品HTTP能力；初始化遇到同名schema拒绝覆盖。

用户已有application配置、launch pom、AuthController的@ApiLog、AccountRoleProvider/OwnerRoleServiceImpl改动以及其他未跟踪资料不纳入本轮提交。LoginThrottleServiceImpl中用户原有Duration相关改动保留在工作区，使用局部暂存只提交本轮删除成功重置方法的改动。

## 10. 最终验收状态

- 7个现有功能接口、28个真实浏览器验收场景已执行，通过；没有用尚未执行的步骤填充通过数。
- 逐次HTTP证据：`docs/testing/api-review-fixes-network-20261004.csv`，共68条捕获记录。包含环境预检的失败记录，需结合第6节解读；不是全部请求都预期200。
- 验收schema的原始介绍、联系入口已经通过真正PATCH接口恢复；最后仅用夹具恢复头像/简历公开引用。真实public资料的更新时间仍为2026-09-26 07:34:59.850，未被本轮编辑操作覆盖。
- 最终浏览器已注销；独立schema、夹具账号、4个OSS对象和被忽略的本地验收文件保留，便于复查。没有删除任何bucket对象，没有清空Redis或删除真实业务数据。
- 前端12条测试与类型检查于20:29再次通过；前端生产构建通过。补齐夹具后后端编译打包于20:32:14通过，不把这次跳过测试的打包替代此前81条完整验证。
- 功能分支提交后停止，等待维护者审核；没有合并父分支。
