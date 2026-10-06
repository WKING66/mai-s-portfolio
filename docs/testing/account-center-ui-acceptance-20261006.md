# 账号菜单、个人中心与管理界面验收记录

日期：2026-10-06。功能分支 `feature/account-center-ui`，父分支 `feature/visitor-registration`，起点 `20d50d4`。本记录描述实际操作，不是无人值守脚本；浏览器快照数字索引仅在当时有效，复测应按页面文字或下列稳定字段定位。

## 范围与结果

本轮增加登录账号下拉菜单和普通用户/站长共用的个人中心，支持昵称、头像和修改密码。项目管理入口仅 OWNER 可见；公开站长资料仍由原管理接口维护，不等于登录用户本人资料。采用 Nuxt UI 4.11.3、Reka 的菜单/表单控件和本地 Lucide 图标，保留 Tailwind 与 A 版配色、光效，不新增认证协议、不使用 ponytail。

已修复：首页跳转整页重载导致的权限闪烁、管理入口永久高亮、背景平铺分段、管理原生表单交互、旧账号请求影响新账号，以及头像异步响应触发的 Sa-Token 上下文异常。删除的 `AdminNavigation.vue` 已确认没有剩余引用，由统一 `AdminShell` 和 `AccountMenu` 替代，可从 Git 恢复。

个人中心、项目新建/编辑/发布/下架以及菜单权限已真实前后端联调。单元测试和构建是补充证据。尚未执行的故障/并发压力边界见末尾，不能据此宣称所有边界已验收。

## 环境与隔离

- 前端最终生产构建预览：`http://127.0.0.1:3004`；后端：`http://127.0.0.1:19334`。
- PostgreSQL：本地 `portfolio_dev`，UI 使用 `project_acceptance_20261005` schema；API 集成测试使用 `api_acceptance_20261004` schema。V2 仅迁移上述专用 schema，没有修改 `public` 业务数据。
- Redis：本地 6379；UI 键前缀 `mai-portfolio:account-ui-20261006`，API 测试独立前缀。没有操作 Docker。
- 文件存储：真实私有 OSS bucket `amai-portfolio`。本轮上传的头像对象保留，没有删除/覆盖原对象。
- 站长验收账号 `project_owner`；新增普通账号 `account_ui_20261006_01`。密码只从本地被忽略的验收文件读入，不写入本文、截图、请求日志或提交。
- 未修改/提交维护者的 `application-dev.yaml`、凭据或认证构造器已有空格改动。3000/9333 既有服务不由本轮停止。
- Nuxt 当前代理地址在构建时读取 `NUXT_BACKEND_URL`；本轮预览必须构建时指定 19334。部署公钥需在预览进程环境加载，不能只配置构建环境后假定运行时存在。

## 本人接口：页面、操作、输入、请求与实际结果

所有返回沿用 `R`，成功 `code=OK`。写请求由统一请求拦截器携带当前 CSRF；Cookie 为 HttpOnly，不在前端读取。下表省略 Cookie、CSRF 值与密码密文。

|接口|页面与操作|输入/请求|状态、响应与页面结果|
|---|---|---|---|
|POST `/api/v1/auth/register`（回归）|`/register`，填写用户名/密码，点“创建账号”|`username=account_ui_20261006_01`，`encryptedPassword=[REDACTED]`|200，返回用户名；显示注册成功，不自动创建登录会话|
|POST `/api/v1/auth/session`（回归）|`/login`，上述普通账号，点“登录”|用户名及 RSA-OAEP 密码密文|200，`loggedIn=true, roles=[]`；随后 GET 会话 200，默认进入 `/account`|
|GET `/api/v1/account/profile`|进入或刷新 `/account`，不用额外点击|无客户端账号 ID|200；新账号初始显示用户名，昵称/头像更新后再登录读取到持久化资料|
|PUT `/api/v1/account/profile`|`/account`，`#account-nickname` 输入“阿霾验收访客”，点“保存资料”|`{"nickname":"阿霾验收访客"}`|200；返回新昵称，提示“昵称已保存。”，页面及顶栏立即同步，无须整页刷新|
|POST `/api/v1/account/avatar`|`/account`，“选择头像”对应 `#account-avatar`，上传本轮附件 PNG|multipart `file`；源图 262×202；不手设 multipart 边界|200；返回 `/api/v1/account/avatar?v=1`，提示头像已更新；资料表 `avatar_media_id=1`；两处头像同步显示|
|GET `/api/v1/account/avatar`|顶栏/个人中心图片自动加载；浏览器额外读取同一路径检查响应|当前 Cookie，本人资料引用；`v`/`rev` 仅刷新标记|200，`image/png`，29660 字节，`Cache-Control: no-store, private`、`X-Content-Type-Options: nosniff`；图片自然尺寸 262×202，重登录和刷新仍能读取|
|PUT `/api/v1/account/password`：错误原密码|`/account`，当前密码输入测试假值，填写合规新密码与确认密码，点“更新密码”|`oldEncryptedPassword/newEncryptedPassword=[REDACTED]`|422 `VALIDATION_FAILED`，“当前密码不正确”；仍在个人中心，GET 会话 200 且 `loggedIn=true`|
|PUT `/api/v1/account/password`：正确修改|同页，用真实当前密码；新密码输入首尾两空格包裹的合规值，确认输入无外侧空格，点“更新密码”|旧密码原样加密，新密码 trim 后加密；现有注册字符/长度规则|200；客户端清空会话/资料，进入 `/login?passwordChanged=1`，提示密码已更新；GET 会话 200 且 `loggedIn=false`|
|POST `/api/v1/auth/session`：改密后|登录页先输入旧密码，再输入新的规范化密码|两次独立登录，不记录敏感值|旧密码 401 `AUTH_INVALID_CREDENTIALS`，留在登录页；新密码 200，回到个人中心，昵称和头像保留|
|DELETE `/api/v1/auth/session`（回归）|点顶栏账号，菜单“退出登录”|统一 CSRF 写请求|200；回到首页，账号入口显示“登录”，头像/昵称与管理权限清空|

## 权限、导航与菜单

1. 匿名：顶栏显示登录入口；个人中心五个接口的匿名访问均在真实 API 测试中返回 401，不靠隐藏按钮保障安全。
2. 普通账号：菜单仅“个人中心”“退出登录”。直接浏览 `/admin/projects` 被路由守卫转到 `/forbidden`；手动请求 `GET /api/v1/projects?view=MANAGE` 返回 403，未降级公开结果。
3. 普通账号缺 CSRF 的 PUT 昵称请求返回 403；随后 GET 会话 200、仍登录，未将 CSRF 错误伪装为注销。
4. 站长 `project_owner` 登录：200、角色来自服务端 OWNER；菜单含“管理项目”“个人中心”“退出登录”。点“管理项目”进入管理列表；最终预览进程重启、浏览器重开后重新读取 Cookie 会话，仍恢复站长入口和技术标签。
5. 菜单 Escape 关闭并恢复触发按钮焦点；菜单项切路由后关闭。头像隐藏文件输入的外层标签补了键盘可见焦点。
6. 首页点击“项目”到 `/#projects`，点击“博客”到 `/#blog`：分别只有项目/博客拥有 `aria-current=location`，页面标记 `same-document` 保留，未整页重载、未丢失 OWNER。顶栏没有固定“管理项目”栏目。
7. 管理页公开栏目均无当前位置标记；管理内部只有“项目管理”或“站长公开资料”当前项，不会干扰首页栏目高亮。
8. 权限保存在当前 Nuxt 应用的共享状态，不写入 localStorage。刷新通过现有 Cookie 会话重新确认；确认前只显示稳定的加载入口，不渲染管理内容。资料与在途请求按应用/会话世代隔离。
9. 旧 403 刷新期间切账号/同用户名重新登录/注销/刷新失败的导航竞态已通过真实拦截器函数的受控异步测试验证；不是在浏览器中手工制造 Redis 故障的证据。

## 项目管理组件回归：真实页面链路

使用本轮新建项目 ID=15，不改变原四个已发布验收项目。

|接口|页面与操作|参数|结果|
|---|---|---|---|
|GET `/api/v1/projects?view=MANAGE&page=1&size=20&status=DRAFT`|`/admin/projects`，点“状态筛选”，选择“草稿”|DRAFT；选项用真实 Nuxt UI 弹层选择|200，总数 10，界面 10 张草稿卡，与筛选值一致|
|GET `/api/v1/admin/tags?kind=TECH`|点“新增项目”，新建页自动读技术标签；保存跳详情再次读|TECH|200；Java 标签按钮持续存在，不因路由切换丢失|
|POST `/api/v1/admin/projects`|`/admin/projects/new`，填写字段，选择 Java，点“保存项目”|slug=`ui-components-review-20261006`；标题“组件交互验收”；摘要“验证组件库表单和管理入口”；贡献“完成账号中心与项目管理交互验证”；tagIds=[1]；无图片/外链|200，id=15，DRAFT，version=0；跳 `/admin/projects/15`，无重复创建|
|GET `/api/v1/admin/projects/15`|保存后的编辑页自动读取|id=15|200，version=0；内容回填，Java `aria-checked=true`|
|PATCH `/api/v1/admin/projects/15`|编辑页改标题为“组件交互验收 · 已更新”，点“保存项目”|version=0，原内容与标签保持|200，DRAFT，version=1；显示干净快照，发布按钮可用|
|POST `/api/v1/admin/projects/15/publish`|同页点“发布项目”|version=1|200，PUBLISHED，version=2；按钮切换“下架项目”|
|GET `/api/v1/projects?view=PUBLIC&page=1&size=12`|发布后浏览器读取公开列表检查|固定 PUBLIC|200，总数 5，包含 ID=15|
|POST `/api/v1/admin/projects/15/unpublish`|同页点“下架项目”|version=2|200，DRAFT，version=3；按钮切回发布|
|GET `/api/v1/projects?view=PUBLIC&page=1&size=12`|下架后下一次读取|固定 PUBLIC|200，总数 4，不含 ID=15；本轮项目最终保留为草稿|

管理列表/编辑页改为 Nuxt UI 按钮、选择、标签、提示和卡片。失败不回填旧列表，不自动重放写操作；保存/发布期间禁止重复操作，重新读取的脏表单需确认丢弃。对应逻辑测试覆盖权限不足、加载失败、标签/外链拷贝、重复写入防护与版本更新，原业务接口没有重做。

## 显示与本轮发现的失败

- 桌面 1902×984：匿名登录、注册文档宽高均为 1902×984，未出现下拉滚动条。
- 浏览器内嵌 320×568 注册视口：文档 320×568，无横向溢出，密码字段可见；导航控件最右边为 304。此为真实浏览器嵌入视口，不等于实体手机验证。
- 深色：根节点 `data-theme=dark` 与 `.dark` 同步，菜单背景 `rgb(16,29,40)`；管理主区透明，固定光效层 `no-repeat`，没有背景平铺分段。浅色个人中心也已视觉检查。
- 首次预览未加载运行时 RSA 公钥：前端显示未配置公钥，不发送注册请求。补齐本轮测试进程环境后注册成功，没有明文降级。
- 头像初始采用异步流：图片返回后出现 Sa-Token 上下文错误。改为标准同步 `InputStreamResource`，保留鉴权；复验 GET 200、正确 MIME/缓存/关流，最终测试后端日志无该异常。
- 重新构建曾未指定验收代理，指向默认旧后端，刷新进入登录页。仅修正预览构建/启动环境，没有修改维护者配置；最后在正确隔离后端重新验证站长刷新恢复与完整项目链路。
- 中断后的测试进程/浏览器会话停止，恢复本轮专用服务与新的独立浏览器会话后继续验收，没有操作他人会话。

## 测试、构建与未覆盖风险

已执行：前端 11 个文件 **141/141** 测试通过，Nuxt typecheck 与最终生产 build 通过；后端相关 System 最新报告 **79/79**、Asset **14/14**，本轮新增核心为 Service21+Controller6+Asset14=**41**；真实 PostgreSQL/Redis 的 AccountCenter4、AuthSecurity8、Registration5 共 **17/17**。全模块回归未出现测试失败；Windows 运行中 jar 文件锁解除后 Maven install/打包成功。外部依赖门控跳过用例不算验收通过。

事实与待处理风险：

- `user_profile` 新表采用自增主键、账号唯一索引，表与全部六列 COMMENT 齐全；没有修改 V1。现有环境需应用 V2 才能使用个人中心；本轮只迁移专用验收 schema。
- 登录与改密共用账号行锁；昵称及头像绑定也锁账号以串行首次资料创建。真实高并发/压力测试未执行。
- 密码 DB 提交与 Redis 全会话撤销不具备跨资源原子性；Redis 故障可能密码已改但撤销不完整。登录 DB 提交失败也可能遗留已创建的 Redis 会话。未做断连/提交失败故障注入，不自动重试整个改密请求。
- OSS PUT、资产状态事务和资料绑定不原子，可能遗留 PENDING/FAILED/未绑定 READY 资产。旧头像不覆盖/不删除，没有自动回收。
- 头像业务限制 2 MiB、PNG/JPEG、2048×2048，解码后重编码去元数据；全局 multipart 仍按既有较大上限解析，临时文件和上传频率尚未专项压测，不擅自修改用户开发配置。
- HTTP 下沿用既有 RSA-OAEP 密文传输，不能代替部署 HTTPS；本轮没有改变部署安全边界。
- 屏幕阅读器、实体移动设备、所有浏览器、网络中途断开及所有历史密码兼容没有全量验证。敏感字段过滤已补旧/新密码密文测试；OpenAPI 已描述主要成功/失败响应，但不是穷尽所有基础设施错误。

## 定向阅读文件

- 前端：`app/components/AccountMenu.vue`、`AccountSettings.vue`、`SiteHeader.vue`、`AdminShell.vue`、`AdminProjectList.vue`、`ProjectEditor.vue`、`ProfileEditor.vue`；`app/composables/useAuthState.ts`、`useAccountProfile.ts`、`useSiteNavigation.ts`、`useAdminProjectList.ts`、`useProjectEditor.ts`；`app/api/account.ts`、`authInterceptor.ts`；`app/pages/account.vue`、`login.vue`；对应常量和测试。
- System：`AccountProfileController`、`AccountProfileService/Impl`、`UserProfileDO/Mapper`、`AccountProfileRequest`、`AccountPasswordRequest`、`AccountProfileVo`、`AccountProfileApiVo`、`AuthenticationInterceptor`、`AuthServiceImpl`、`UserAccountMapper`。
- Asset：provider `ImageAssetUploadService`；service `ImageAssetUploadServiceImpl`、`AvatarUploadProperties`、`MediaAssetDO` 和配置元数据。
- 数据与测试：`V2__account_profile.sql`、`AccountCenterApiTest`、`AccountProfileServiceImplTest`、`AccountProfileControllerTest`、`ImageAssetUploadServiceImplTest`、`SensitivePayloadSanitizerTest`。

交付状态：代码和以上证据随功能分支提交，未经维护者审核不得合并父分支或 main。本轮不继续博客/Agent 或通用项目媒体上传。
