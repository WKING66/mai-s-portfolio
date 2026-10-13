# 固定 RSA 认证重构报告

日期：2026-10-01。功能分支：`feature/auth-fixed-rsa`。

后续修正（2026-10-04）：通用登录与站长授权已按用户追加要求分离。
本报告保留当时的实现记录；当前会话入口与权限规则见
[通用认证修正说明](auth-unified-session-correction.md)，不再沿用下文的“仅 OWNER 可登录”描述。

唯一需求与架构依据：`docs/auth-refactor-plan .md`（原文件名在扩展名前有空格，未擅自改名）。

## 1. 结论与范围

代码已从动态 RSA Challenge 改为固定 RSA-OAEP SHA-256；后端完整测试及打包、前端测试、类型检查和生产构建通过。没有修改数据库结构、博客、Agent、作品集展示、OSS 业务实现，或引入 JWT、OAuth、新认证框架、防重放协议。

文档 22 项验收要求的代码实现与本地验证已落实。**不能将此结论视为生产上线验收完成**：实际密钥配置、公网 TLS、反向代理与浏览器 Secure Cookie 的部署验收尚未进行。双实例测试为同一测试 JVM 内的两个独立 Spring Boot/Tomcat 实例，未声称完成独立进程或跨机器演练。

## 2. 当前与目标的差异及实际改动

旧链路：前端 GET challenge → 申请限流 → 分布式锁及容量检查 → 临时 RSA KeyPair → 私钥与挑战写 Redis Hash/ZSet → 前端加密 → 提交 challengeId → Lua 原子消费 → 解密 → Argon2 → Sa-Token。

新链路：

```text
前端部署配置中的固定 Base64 SPKI 公钥
→ Web Crypto RSA-OAEP(SHA-256)
→ POST /api/v1/admin/session { username, encryptedPassword }
→ HTTPS 边界、独立登录限流、Origin
→ 后端配置文件中的固定 PKCS#8 私钥解密
→ 原有账号查询、Argon2 校验、OWNER / ENABLED 校验
→ Sa-Token 登录，Session / CSRF 写入 Redis
→ HttpOnly / Secure / SameSite=Lax / Path=/ Cookie
```

- 新增 `PasswordCryptoService` 接口及实现，只有 `decryptPassword` 一项业务能力。启动时加载私钥，不生成、签发、缓存挑战；每次创建独立 Cipher，并清理解密字节副本。
- RSA-2048、OAEP SHA-256、MGF1 SHA-256 与浏览器一致；格式、长度、padding 或 UTF-8 解码失败统一返回 `400 / AUTH_INVALID_CREDENTIAL_PAYLOAD`。
- 用户不存在和密码错误统一为文档指定的 `401 / AUTH_INVALID_CREDENTIALS`，不向外区分失败原因。旧 `BAD_CREDENTIALS` 响应码已替换，中文提示和内部失败分类保留。
- LoginRequest 仅保留 username / encryptedPassword；登录字段校验改为 400，其他业务接口仍保留原有 422 校验语义。
- 前端不再获取公钥或 challenge；登录只有一个 POST；成功或失败后均清空组件密码，未引入客户端密码存储。
- 主配置要求 HTTPS，dev 明确关闭此要求并保持回环 HTTP 联调；prod 仅监听回环，使用 Tomcat 可信代理协议还原，Cookie 明确设置四项属性。
- 更新 Knife4j/OpenAPI 登录说明和契约测试，不再暴露 Challenge operation 或 schema。
- 删除挑战配置、挑战申请限流及容量逻辑；Redis starter 仅保留现有 Redisson、登录限流和会话支持。

## 3. 删除清单与引用检查

删除前搜索了生产代码、测试、配置及前端引用。下列通用包装在当前代码中没有挑战之外的生产调用者，因此随挑战删除，而非为未来扩展保留：

- system：`LoginChallengeService`、`LoginChallengeServiceImpl`、`LoginChallengeVo`、`LoginChallengeApiVo`、旧挑战测试。
- common：`DistributedLockService`、`LockAcquisitionException`。
- redis：`ExpiringStringMap`、`RedissonExpiringStringMap`、`RedissonDistributedLockService`、旧锁测试及对应自动配置。
- RedisKeys 中的挑战命名空间、挑战锁、挑战申请限流和短时映射键生成。
- RedisLuaScripts 中的 MAP_PUT / MAP_TAKE / MAP_SIZE。
- challenge endpoint、challengeId、临时密钥、挑战 TTL、容量与清理/消费逻辑；测试配置中的映射/锁替身。

旧 lock / store 等空目录已清理，没有删除其他业务文件。历史文档未改写。脱敏名单中的旧 challengeid 名称仅作为敏感字段保护保留，并不参与任何认证协议或状态。

## 4. 保留能力与文档冲突处理

| 内容 | 处理及原因 |
| --- | --- |
| 接口名称 | 保留原 GET / POST / DELETE `/api/v1/admin/session`；GET 即文档的 /me 能力。不为了示例重命名现有 API。 |
| Service 形式 | 沿用接口 + service.impl，满足项目现有规范；不新增 crypto 策略、适配器或模块层。 |
| 账号、Argon2、角色 | 原数据库字段、哈希算法、大小写不敏感用户名查询、OWNER/ENABLED、Sa-Token 角色注解与角色提供器不变。原停用或普通账号仍拒绝管理登录，既有会话权限变化仍重新检查。 |
| 校验次序 | 保留先验证密码再返回账号权限失败，避免改变既有认证语义；不是扩展账号体系。 |
| Origin | 保留现有严格规则：缺少 Origin 也拒绝登录；文档允许依部署/接口类型选择。 |
| 会话和 CSRF | 保留 Redis-backed Sa-Token、随机会话 CSRF、写请求检查、登出失效；不改为 JWT。 |
| Redis Lua | 删除所有 Challenge Lua，但保留 RATE_ACQUIRE 和 UPDATE_STRING_KEEP_TTL：分别用于文档要求保留的原子登录限流及 Redis 3.2 会话 TTL 兼容，不属于挑战状态机。 |
| 日志 | 保留 @ApiLog、滚动日志与既有脱敏实现；补测完整密文、私钥、密码、CSRF 和 Session Token 均不进入日志。 |
| 错误码兼容 | 按文档第 17 节把凭据失败码改为 AUTH_INVALID_CREDENTIALS；当前前端未依赖旧码。外部调用方如曾依赖 BAD_CREDENTIALS，需要同步。 |
| 多实例测试描述 | 文档同时禁止公钥接口，故测试从部署公钥配置直接加密，不实现“A 返回公钥”的示例步骤。 |
| 数据与其他模块 | 不变更 SQL、表或缓存命名格式；不清空真实会话、不扫描/删除生产挑战旧数据，其原 TTL 自然过期。 |

原有未提交的 launch/pom.xml、AccountRoleProvider、OwnerRoleServiceImpl 修改保持原样，未混入本轮变更暂存。

## 5. 配置与部署待办

- 后端根 `.env`：`AUTH_RSA_PRIVATE_KEY_LOCATION` 指向**外部 RSA-2048 PKCS#8 PEM 私钥文件 URI**，例如 `file:F:/secrets/mai-portfolio/auth/private.pem`。私钥不存在、格式错误或大小不符时拒绝启动，不回退到自动生成。
- 前端 `frontend/.env` 或部署环境：`NUXT_PUBLIC_AUTH_RSA_PUBLIC_KEY` 为同一密钥对的 Base64 DER/SPKI 公钥。运行 Nuxt 生产服务时仍须正确注入公开配置；私钥不得填写到任何 NUXT_PUBLIC 配置中。
- 本轮没有生成或提交开发/生产固定私钥，也没有擅自向现有 `.env` 写入密钥。当前根 `.env` 尚无新的私钥位置配置，**正常开发启动与手工登录需先配好密钥对**。测试仅使用进程内生成的测试密钥，临时私钥文件在测试进程结束后清理。
- 现有 `backend/run-local.ps1` 已加载并要求私钥位置；开发仍使用 dev 配置。生产必须显式启用 prod，提供数据源与其他既有部署配置。
- Nginx / Gateway 需终止 HTTPS，HTTP 入口跳转 HTTPS；`/api/v1/` 直接代理到同机 Spring Boot 回环端口，并**覆盖** X-Forwarded-Proto 为真实协议、X-Forwarded-For 为真实客户端地址。
- Nuxt 生产监听同机回环（例如 `NITRO_HOST=127.0.0.1`），避免外部绕过 TLS 入口走前端 API 代理。应用的可信代理列表只含回环地址，不能放宽为任意来源。
- 本轮未修改部署服务器、域名、证书、Nginx 或本地 Docker。必须在真实部署环境检查 HTTPS 识别、Secure Cookie 回传和代理 IP 限流；不能仅凭配置文件认为部署安全已完成。

## 6. 验证结果

最终验证：Java 21；本地 PostgreSQL 开发库；Redis 端口 16379。启动命令所需秘密从被忽略的 .env 载入，未打印凭据。

- 后端：`mvn -B -f backend/pom.xml verify`，开启 RUN_REDIS_INTEGRATION_TEST，测试进程使用 REDIS_PORT=16379；34 模块构建/打包成功。
- Surefire 合计 67 项：**66 通过、0 失败、0 错误、1 跳过**。唯一跳过是真实 OSS 上传测试，与认证改动无关；认证和数据库测试没有跳过。
- 固定密钥测试：正确密码、190 字节边界、Unicode、非法 Base64、错误长度、随机密文、错误 OAEP/MGF1、非法 UTF-8、错误私钥、两个解密实例与 24 个并发请求。
- 认证集成：正确登录、密码错误、不存在用户、空参数、明文请求、非法密文、普通账号、停用账号、权限变化、Origin 缺失/非法、当前会话、缺失/错误/有效 CSRF、登出后旧 Cookie 失效。
- 双真实 HTTP 实例：相同固定公钥密文分别由 A/B 登录；A 登录后的会话可由 B 查询；跨实例登出；实际 Redis token 键存在；Cookie 四属性；A 连续 5 次错误登录后 B 返回 429。
- 日志：检查密码、完整密文、私钥、CSRF、实际 Cookie token 未出现在捕获日志中，注解范围保持不变。
- Swagger/Knife4j、数据库结构、资料展示/编辑、启动与模块规范回归通过。
- 前端 Node 24：Vitest 4/4、Nuxt typecheck、Nuxt build 成功；验证仅一次 POST、Payload 只有两个字段且不包含明文、OAEP 可解密、公钥缺失与密码过长拒绝。
- `git diff --check` 无空白错误。曾遇到沙箱阻止 JUnit 临时文件清理，提升执行权限后正常通过；未为通过测试改业务实现。

## 7. 文档第 23 节逐项验收

“已落实”指代码/本地测试，不等于已经实际部署。

| # | 验收要求 | 状态 / 证据 |
| --- | --- | --- |
| 1 | 生产设计明确要求 HTTPS | 已落实：默认开启 + prod 回环/可信代理；实际 TLS 部署待验。 |
| 2 | Payload 不出现原始 password | 已落实：两字段请求与前端测试。 |
| 3 | OAEP + SHA-256 | 已落实：双方参数、错误参数与往返测试。 |
| 4 | 不提供 public-key 接口 | 已落实：没有控制器，404 测试。 |
| 5 | 私钥不进入前端 | 已落实：前端仅公开 SPKI 配置。 |
| 6 | 私钥不提交 Git | 已落实：外部 Secret 文件加载，测试临时密钥未入库。 |
| 7 | PostgreSQL 只存 Argon2 hash | 保留并通过数据库/认证回归；无密码字段迁移。 |
| 8 | 登录继续使用 Sa-Token | 已落实：原认证编排保留。 |
| 9 | Session 继续进入 Redis | 已落实：真实 DAO / token 键 / 跨实例会话测试。 |
| 10 | Cookie HttpOnly | 已落实：MockMvc 与真实 HTTP 响应断言。 |
| 11 | 生产 Cookie Secure | 已落实：prod 配置及响应头断言；真实浏览器 HTTPS 回传待部署验。 |
| 12 | CSRF 有效 | 已落实：缺失/错误拒绝、正确跨实例登出。 |
| 13 | 登录限流有效 | 已落实：真实 Redis、跨实例 429 和窗口/重置测试。 |
| 14 | Origin 有效 | 已落实：缺失与非法来源拒绝。 |
| 15 | 删除一次性 Challenge | 已落实：接口、服务、VO、前端调用删除。 |
| 16 | 删除 Challenge Hash/ZSet | 已落实：存储包装、键定义和配置删除。 |
| 17 | 删除 Challenge Lua | 已落实：MAP_* 全部删除。 |
| 18 | 删除 Challenge 分布式锁 | 已落实：锁调用及孤立包装删除。 |
| 19 | 删除动态 RSA KeyPair | 已落实：生产源代码不生成密钥，只有测试生成测试用密钥。 |
| 20 | 多实例直接处理登录 | 已落实：两套真实 HTTP 应用实例；独立进程/跨机部署演练未做。 |
| 21 | 单测和认证集成测试通过 | 已落实：最终 verify 成功，认证集成无跳过。 |
| 22 | 日志不泄露凭据 | 已落实：真实登录日志与脱敏测试。 |

## 8. 风险和主动接受的取舍

- 固定 RSA 密文可以再次用于登录，不具备一次性挑战语义。测试明确接受重复密文；依赖 HTTPS，不补造防重放协议。
- 配对密钥需受控配置与同步轮换；公钥/私钥错配会导致 400。缺失真实密钥是当前手工开发登录的配置阻断，不可用测试密钥充当生产配置。
- RSA-2048 的 OAEP SHA-256 密码上限为 190 UTF-8 字节，多字节字符不能按字符数计算；沿用现有前端限制，没有扩展密码管理功能。
- JavaScript/JVM String 不能保证立即擦除；只清理组件引用和字节副本，不声明彻底内存消除。
- 未进行浏览器完整 E2E、真实公网 TLS、不同进程/机器、多机代理链、密钥轮换或 Redis 故障演练。没有把这些未做项目算成生产验证通过。
- 原有成功登录重置 IP 限流、账号不存在时不执行 Argon2 的行为保留；没有额外增加用户名限流或时间侧信道协议。
- 全量回归未上传 OSS；本轮没有文件存储业务变更，不新增与认证无关的外部副作用。

## 9. 核心定向阅读文件

以仓库根目录为基准：

- system-service 的 `service/PasswordCryptoService.java`、`service/impl/PasswordCryptoServiceImpl.java`：唯一解密职责。
- 同模块 `service/impl/AdminSessionServiceImpl.java`：认证主链路及统一错误码。
- 同模块 `controller/AdminSessionController.java`、`entity/request/LoginRequest.java`：API / Swagger / 参数契约。
- 同模块 `auth/AdminAuthorization.java`、`service/impl/LoginThrottleServiceImpl.java`、`config/SecurityProperties.java`：HTTPS、CSRF、角色、Origin 与限流配置。
- redis starter 的 `define/cache/RedisKeys.java`、`define/RedisLuaScripts.java`、`autoconfigure/RedisInfrastructureAutoConfiguration.java`：旧挑战能力移除、保留脚本。
- security starter 的 `session/NamespacedSaTokenDao.java`、`password/Argon2PasswordHasher.java`：未改动但必须保留的会话/存储能力。
- launch 的 `src/main/resources/application.yml`、`application-dev.yaml`、`application-prod.yaml`。
- launch 的 `src/test/java/dev/amai/portfolio/RedisAuthFlowTest.java`、`AdminSecurityTest.java`、`ApiLoggingTest.java`、`OpenApiContractTest.java`、`AuthKeyTestSupport.java`。
- system-service 的 `src/test/java/dev/amai/portfolio/system/service/impl/PasswordCryptoServiceImplTest.java`。
- `frontend/app/api/session.ts`、`loginCrypto.ts`、对应测试、`frontend/app/pages/admin/login.vue`、`frontend/nuxt.config.ts`。
- `.env.example`、`frontend/.env.example`、`backend/run-local.ps1`：密钥配置入口。
