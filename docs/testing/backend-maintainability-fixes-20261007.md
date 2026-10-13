# 非资产后端审查问题修复与维护性复核

日期：2026-10-07。

本轮范围：R01、R02、R03、R05、R07、R08、R09。维护者明确要求暂不改文件上传、图片处理、OSS、媒体代理及资产用途。因此 R04（异步媒体流生命周期）与 R06（用途依赖存储路径）保留待后续资产框架设计；不以本轮测试通过宣称资产方案已验收。

## Git 与既有工作

- 修复分支：`feature/backend-maintainability-fixes`。
- 父分支：`feature/api-feedback-alerts`，父提交 `263201a`。
- 隔离工作区：`F:/code/ai/codex/projects/mai-portfolio/.local-backups/fix`。
- 原工作区、暂存区、Common 常量迁移、运行配置及上传代码中的既有修改保持原样。
- 修复分支从父提交出发，承接了将登录/注册合入 AuthController 的意图。没有把维护者未完成的全部整理打包提交。领域消息仍使用父分支已有的 System 常量；审核后整合 Common 迁移时须按具体冲突核对，不自动覆盖。
- 不重启默认服务，不修改默认数据库/Redis/OSS，不合并或推送；提交后交维护者审核。

## 实现前的业务判断

稳定核心：

1. 登录与注册分别使用原有 session/register 路由，账号类型和启用状态由服务端控制。
2. 认证入口必填/凭据格式为 400，业务政策为 422；普通模块的 DTO 校验不被认证处理器接管。
3. 注册和改密的新密码政策相同：移除边缘空白、允许可见 ASCII、至少 12 个字符、受固定 RSA-OAEP SHA-256 明文容量约束。旧密码和初始 OWNER 密码不套用该归一化。
4. 改密成功后旧令牌必须失效；数据库回滚时有效旧令牌不得因此被销毁。不能把可靠性建立在数据库提交后的 Redis 删除上。
5. 项目公开/管理视图属于 Portfolio，MANAGE 所需账号、OWNER 角色及 HTTPS 由 System 的授权能力核对。
6. 技术标签选择仅支持 TECH；现阶段不新增 TOPIC 业务功能。

已接近的变化及归属：新密码政策调整由 System 的 NewPasswordPolicy 承载；会话实现替换通过 AccountAuthenticationService；同类业务声明 OWNER 访问通过 System provider 的 OwnerAuthorizationService；标签选择范围由 TaxonomyQueryService 实现决定。固定密文容量由 PasswordCipherConstants 与 Crypto 实现共同使用，避免初始化/注册/改密各写一份 190。

可复用能力：保留已有 PasswordHasher、PasswordCryptoService、限流组件、System provider、通用 Web 异常响应和 ProjectService 的视图解析。没有引入通用 Registry、Factory、Strategy 或抽象基类。

## 功能正确性

| 编号 | 结果 |
|---|---|
| R01 | 经后续审核进一步修正：认证服务入口复用字段约束，抛 InvalidCredentialPayloadException，由 System 的 AuthRequestExceptionHandler 翻译为 400。普通 MethodArgumentNotValidException 只由通用 GlobalExceptionHandler 映射为 422。具体事务边界和验证记录见 auth-exception-boundary-fix-20261007.md。 |
| R02 | 合并后的 AuthController 显式区分 GET/POST/DELETE /api/v1/auth/session 和 POST /api/v1/auth/register。移除独立 RegistrationController，不依赖请求 DTO 类型选择路由。 |
| R03 | 新增持久化 credential_version；密码和版本在同一条件 UPDATE、同一事务中更新。每个独立 token 保存登录时版本，受保护请求读取当前数据库版本。旧 token 不匹配或无版本返回 401；Redis 读故障不会放行。提交后没有外部会话删除，仅记录成功日志。 |
| R05 | 注册和改密注入同一个 NewPasswordPolicy。旧密码保持原文；注册用户名归一化由 Service 处理，移除 DTO 中相同的业务归一化。 |
| R07 | 移除 System 的 ProjectViewAccess 及项目路径/view/MANAGE 常量。ProjectService 的 MANAGE 分支调用 OwnerAuthorizationService，公开查询不要求登录。Controller 仍负责拒绝重复 HTTP 参数，Service 拒绝非法视图。 |
| R08 | Controller 只绑定 kind 并投影返回结构；TaxonomyQueryService 的实现校验支持范围。无参数 techTagOptions 保留供现有 Portfolio 调用。 |
| R09 | 首次 OWNER 创建前校验初始密码 UTF-8 容量；原始值不 trim、不套用普通注册字符集。OWNER 已存在时既不重置，也不重新验证环境密码。 |

凭据版本方案：

- 数据库的 credential_version 是失效的 Source of Truth；token 中保存的版本只是登录时快照，必须与数据库核对。
- 新登录强制独立 token，避免会话供应商的共享 token 配置让新登录改写旧 token 的版本。
- 账号有效性、版本核对、会话创建/退出集中在 AccountAuthenticationServiceImpl；AuthServiceImpl 和 AccountProfileServiceImpl 不直接调用 StpUtil。
- 不按账号在 afterCommit 批量删除会话，避免 Redis 故障误报“改密失败”，以及误删提交后用新密码建立的令牌。旧记录由已有会话 TTL 回收。
- 缺少版本的历史令牌需重新登录；这是部署时的明确行为。
- 已经进入执行阶段的请求不会被倒推取消。个人资料写入会再次锁定并核对账号/版本；不新增分布式事务或请求取消体系。

## 设计与职责边界

System 拥有账号有效性、新密码政策、登录与凭据版本、标签选择范围、OWNER 能力及传输政策。Portfolio 拥有项目视图及何时要求授权。Web starter 只做通用异常翻译。Controller/DTO 保留协议绑定、必填字段和格式约束，不承载此次收拢的业务政策。

新增服务沿用模块现有 service/impl 目录，策略组件放 component，HTTP 适配放 auth；没有创建 system/tag/service 或新的嵌套三层目录。

注册用户名业务格式/长度在 Service 的既有规则中统一验证，因此非法注册用户名为业务 422；缺少用户名/密文仍为认证 400。64 位用户名带边缘空白仍可先规范化后注册。前端正常发送已归一化用户名的流程不变。登录用户名归一化也从 DTO 移入 Service，保留旧密码原文语义。

## 当前支持的扩展点

- 改新密码规则：主要修改 NewPasswordPolicy；无需同时修改注册和改密两个服务实现。
- 替换会话供应商：主要替换 AccountAuthenticationService 的适配实现。System 的框架拦截器/角色适配仍属供应商适配范围，不能声称全项目只改一个文件；注册/改密业务不需要遍历修改会话 API。
- 新增需要 OWNER 的同类业务入口：调用 provider 能力；不需要往 System 追加该业务的路径/参数特判。
- 修改技术标签选择范围：由 System Service 拥有规则。现有 TECH 投影契约保留；未来 TOPIC 需要明确相应数据契约，不自动借用 TECH 数据。
- 固定密文协议改变：容量来源集中；仍须同步前端协议及部署密钥，未实现未经确认的协议版本协商。

## 主动没有提前设计的未来扩展

不设计项目资产文件管理框架，不调整上传接口、图片组件、二进制代理或资源 URL 策略。不新增 TOPIC 写接口、角色注册表、会话供应商工厂、MQ、Outbox、后台撤销工作器或通用权限引擎。凭据版本解决已存在的会话失效要求，不为假设性的认证功能扩张。

## Maintainability Review

| 项目 | 结论 |
|---|---|
| 同类业务类型的改动面 | OWNER 访问能力可由调用域复用；新业务不再修改 System 的项目路径分支。标签类型扩展在 System 的业务契约/查询实现处理，不能仅改 Controller。 |
| 替换外部依赖 | PasswordHasher 边界不变；会话业务调用依赖接口，供应商耦合保留在 System 适配/框架范围。OSS 完全未调整。 |
| 重复规则 | 新密码判断已收拢；项目视图含义不再在两个模块各解释一次；用户名归一化从 DTO 移至服务；密码容量共用协议常量。 |
| 为测试而写生产逻辑 | 无测试类名、测试账号、临时端口或测试专属分支进入生产逻辑。测试使用独立内存 DAO/替身验证真实失效规则。 |
| Source of Truth | 密码失效为数据库版本；新密码政策为单一组件；项目视图为 Portfolio；标签支持范围为 System Service。 |
| 失败路径 | 回滚不销毁会话、旧凭据输入错误维持 422、旧 token/历史无版本 token 拒绝、会话删除不可用不影响失效、新登录不恢复旧 token、停用账号仍可退出、初始化超长密码不入库。 |
| 配置/异常/日志 | 沿用现有安全配置、统一 ApiException/错误响应、现有 Logger。没有记录密码、密钥、token 或厂商异常正文，没有更改运行配置。 |
| 后续复制压力 | 注册/改密和管理视图授权不再要求复制判断。文件流生命周期仍有复制，按维护者范围本轮保留。 |
| 抽象是否对应真实需求 | 两个业务服务共用账号认证边界；项目管理视图需要跨模块 OWNER 能力；注册和改密共用密码政策。未因未来假设增加调度/策略结构。 |

## 技术债务与部署边界

1. R04、R06 及资产对账、未绑定资源清理、媒体 URL/存储异常语义待资产管理链路设计。本轮不得称这些问题已解决。
2. 现有 Common 常量迁移未纳入修复分支；原工作区仍含未完成整理。审核后整合需核对冲突、归属及编译。
3. V3 数据迁移必须先于新版账号查询生效；已提供 Flyway 迁移文件，未应用到真实数据库。旧节点不做版本检查，部署需要整体切换新版认证节点，不能混用旧节点继续受理受保护请求。
4. 旧 token 会因无版本而需重新登录；残留会话记录使用既有 TTL，不增加主动后台清理。
5. 实际 PostgreSQL 迁移与真实 Redis 多实例流程未在默认环境执行；不能将内存会话回归描述为真实依赖验收。
6. 运行中的默认前后端未重启，修复交付位于独立分支，原工作区的启动阻断要在审核整合后才会消除。

## 测试结果

最终结果以本文件末尾的验证记录为准。验证采用 Java 21、本地 Maven 缓存、工作区测试临时目录，禁用需显式环境授权的 PostgreSQL/Redis/OSS 集成测试。Full BootContextSmokeTest 使用模拟 RedissonClient 装配完整应用，验证真实 MVC 注册及基础注解行为，不连接默认 Redis。

重点回归：

- 当前登录/注册/会话读取/退出四种映射、认证 400 与其他业务 422 分离。
- 真实 Sa-Token + 内存 DAO 的 token 版本生产/消费；模拟删除异常，两个旧 token 仍拒绝，新 token 独立且有效，历史无版本 token 拒绝。
- 密码与版本由同一个条件 UPDATE 写入；回滚不撤销会话；旧密码错误、冲突和无事务调用仍有明确失败语义。
- 项目管理视图授权失败阻止数据库查询；公开错误参数不调用授权；重复 HTTP view 参数拒绝。
- TECH 选择契约及不支持类型拒绝；超长/多字节初始密码、边界值、已存在 OWNER 不重置。
- 原有上传/存储测试仅作为全模块回归运行，其生产代码和测试文件均未修改。

### 最终验证记录

- Java 21，离线 Maven 全模块 package 成功：共 212 项测试，实际执行 168 项，失败/错误均为 0；44 项依外部环境条件跳过。成功时间 2026-10-07 19:45:36。
- 随后将登录与当前账号的启用状态判断收拢到认证边界，增加禁止停用账号直接建会话的回归。末次受影响流程复测 58 项全部通过（AccountAuthenticationServiceImplTest、AccountProfileServiceImplTest、RegistrationServiceTest、AuthContractTest、BootContextSmokeTest），成功时间 19:48:35。
- 最终源码重新执行全模块 package（复用已完成测试，skipTests），打包成功，时间 19:50:45；不是把跳过测试的打包描述为测试通过。
- OCR delegate 解析 26 项可审查差异，逐项人工复核 26/26，skipped_files=0，coverage_rate=100%。其默认排除的 17 项测试、删除代码及本报告另外按上下文检查。未调用外部 OCR 模型生成修复结论。
- 原工作区审查范围内源码哈希变化为 0；Asset/Storage、公开媒体 Controller 和 AccountProfileController 差异为 0。AccountProfileServiceImpl 中 uploadAvatar/openAvatar 两个方法的代码与父提交相同，只调整共享认证及改密职责。
- git diff --check 通过；提交候选为 43 项相关 Java、SQL 和本报告，不包含配置、凭据、上传模块、日志或编译输出。
- 真实 PostgreSQL/Flyway 数据迁移、Redis 多实例及默认环境页面操作未执行。没有写入真实依赖，也没有重启默认服务。

回归和打包输出保存在原工作区受忽略的 .local-backups/fix-package.log、fix-final-focused.log、fix-final-package.log；机器覆盖记录为 fix-final-review.json。它们不进入功能提交。
