# Redis 鉴权基础设施与 Key/Lua 集中管理：开发交接

日期：2026-09-30（Asia/Shanghai）。作者角色：Coding Agent。

本文记录实现事实、当时的选择依据及已知争议，供新的 Learning Agent 独立检查。本文不是 Learning Pass 的分析结论，不包含教学 Demo、口试题或新增开发任务。交接阶段只新增本文，不修改代码、配置、Spec 或 Task。

## 0. 版本、范围与工作区状态

- 仓库：`F:/code/ai/codex/projects/mai-portfolio`。
- GitHub：`https://github.com/WKING66/mai-s-portfolio.git`。
- 功能分支：`feature/object-storage-foundation`。分支名沿用已有开发分支，与本轮 Redis 功能名称不一致。
- 本轮代码提交：`b5a07a15572a5d2318c22ae7e6c17f1d4a87a4e2`，`fix: verify Redis expiry and centralize keys and Lua scripts`。
- 本轮差异基线：`473d090`，`feat: add Redis authentication infrastructure`。前一提交已建立 Redis Starter、Redis 会话/限流/挑战和锁抽象；本轮修正过期统计、补充真实测试、统一 Key/Lua、修正脚本环境变量处理。
- 文档作为独立提交跟随代码提交。提交与推送目标是上述功能分支；本次不合并 `main`。
- 有意保留且不纳入本轮提交：用户未暂存的 `backend/mai-portfolio-launch/pom.xml`；未跟踪的 `.agents/skills/learning-agent-skill/` 和 `backend/mai-portfolio-launch/logs/`。根目录 `.env` 不提交，也不在本文复制任何凭据。
- 上轮验证使用的工作区包含上述未暂存 POM 改动，因此测试通过不等同于已在全新、完全干净的 Git checkout 上重新验证。

代码差异可用 `git diff 473d090 b5a07a1 -- backend` 定向阅读。前一阶段的 Redis 基础设施可用 `git show 473d090` 阅读；不要把整个分支相对 `main` 的所有历史功能都归入本轮。

## 1. 完成需求与 Spec / Task 对应关系

| 本轮完成事项 | 对应依据 | 范围说明 |
| --- | --- | --- |
| 短时映射改为 Hash + ZSet + Lua，消费时检查过期时间，统计前清理过期条目 | `tasks.md` T076：逐条 TTL、原子取出与真实 Redis 验收 | 本轮替换先前的 `RMapCache` 实现，服务接口不变；不是新增登录协议 |
| Redis Key、登录相关命名空间及自定义 Lua 分别集中到单个文件 | 用户直接补充要求；T076 的统一应用键前缀；`springboot-nuxt-code-style.md` 第 32 节；`coding-standards-template.md` Redis Key 目录约定 | 该补充没有单独 Task 编号，不应虚构对应任务 |
| Sa-Token DAO 复用集中 Key/Lua 定义，明确自动配置先后顺序 | T076；T074 的基础设施 Starter 边界 | 保持 Sa-Token 鉴权能力，不新增自定义角色系统 |
| 验证真实 Redis 的限流、锁、消费/过期和 Sa-Token 字符串、对象、会话行为 | T076、T002 的兼容验证要求 | 实际服务版本是本地 Windows Redis 3.2.100；未直接验证用户最初提出的 Redis 服务端 3.2.1 |
| 本地启动/Redis 测试脚本清理陈旧 Redis 环境变量，并把 Sa-Token 集成测试纳入显式 Redis 验收 | T076；本地 `.env` 驱动配置的既有约定 | 本轮没有修改 `.env` 本身 |

相关但不是本轮新交付：T010/T011 的站长登录、Cookie/CSRF 和权限行为；FR-038 的站长项目管理边界；FR-045 的账号类型隔离；FR-018 的内容管理授权。普通访客注册/登录仍属于 T069，不能把本轮 Redis 完成理解为普通用户功能完成。

Spec/计划中的已知差异，保留供独立核对：

- `tasks.md` T076 与当前实现已把 Redis 纳入首版；`plan.md` 仍有“首版不启用 Redis”“Redis 仅用于未来验证”的旧描述。
- 聊天中曾暂定前端 SHA-256、后端再次 SHA-256；当前 `LoginRequest`、`AdminSessionServiceImpl` 和密码实现仍是一次性 RSA-OAEP + 服务端 Argon2id，T010 也仍描述 RSA。该协议差异没有在本轮解决，不能视为 SHA-256 方案已经落地。
- `spec.md` 的整体状态仍为 Draft；T076 勾选完成不能替代并发、故障、上线环境或整站验收。

## 2. 核心模块、目录、类与配置变化

| 归属 | 本轮变化 | 未改变的边界 |
| --- | --- | --- |
| Redis Starter | 新增 `redis/define/cache/RedisKeys.java` 与 `redis/define/RedisLuaScripts.java`；删除 `redis/support/RedisKeyFactory.java`，清理原空目录 | Redis 基础设施仍归 framework，而非 system/common |
| Redis Starter 自动配置 | 注册 `RedisKeys` Bean；限流、锁、短时映射统一注入它 | 依赖 Redisson 自动配置先创建 `RedissonClient`；支持同类型 Bean 覆盖 |
| `RedissonExpiringStringMap` | 从 `RMapCache.put/remove/size` 改为自定义 Lua；每个映射使用数据 Hash 和到期索引 ZSet | `ExpiringStringMap` 的 `put/take/size` 接口未变 |
| `RedissonFixedWindowRateLimiter` | 使用集中 Key 生成和 `RATE_ACQUIRE` 常量 | 固定窗口规则与登录成功后 reset 行为未变 |
| `RedissonDistributedLockService` | 锁 Key 统一由 `RedisKeys.lock` 生成 | 仍使用 `RLock.tryLock(wait, lease, unit)`，未引入续租或 fencing token |
| Security Starter | `NamespacedSaTokenDao` 构造参数由前缀字符串改为 `RedisKeys`；更新 Lua 和 Key 包装/搜索去前缀逻辑集中管理 | 仍继承 `SaTokenDaoForRedisson`；对象/会话的适配逻辑主要继承自依赖 |
| `SecurityAutoConfiguration` | 声明在 `RedisInfrastructureAutoConfiguration` 后装配，DAO 注入统一 Key Bean | Sa-Token 注解拦截器与 Argon2 PasswordHasher 未重新实现 |
| System service | `LoginChallengeServiceImpl`、`LoginThrottleServiceImpl` 改用集中命名空间常量 | 本轮没有修改 HTTP 路由、Request/Vo 和站长业务登录顺序 |
| 测试 | 扩展 `RedissonIntegrationTest`；新增 `NamespacedSaTokenDaoIntegrationTest`；更新锁与 DAO 单元测试的构造接线 | 生产能力与测试替身分开 |
| 脚本 | `run-local.ps1`、`test-redis.ps1` 修正 Redis 密码/可选变量继承；Redis 测试脚本覆盖两个 Starter 的集成测试 | 不启动 Redis、不改 Docker、不建立虚拟盘映射 |

本轮未修改 POM 依赖版本、SQL、前端、OSS 实现、消息系统或 Agent 业务。

既有运行配置仍参与本轮调用链：

- Java 21；Spring Boot 4.1.1；Redisson 4.7.0；Sa-Token 1.45.0；JUnit 6.0.3。版本集中在父 POM、dependencies BOM 及 Spring Boot 管理依赖。
- `spring.data.redis.*`：主机、端口、密码、database、连接/命令超时，来自 `REDIS_*` 环境变量。默认端口 6379、database 0、超时 3s。
- `portfolio.redis.key-prefix`：来自 `REDIS_KEY_PREFIX`，默认 `mai-portfolio`。
- `portfolio.security.*`：挑战 TTL 60s，未消费挑战上限 128；挑战限流 32 次/1m，登录尝试 5 次/10m。
- 挑战签发锁等待 500ms、租期 10s，仍为 `LoginChallengeServiceImpl` 的常量，没有配置化。
- Sa-Token Cookie 名称 `portfolio_session`，HttpOnly、SameSite=Lax；基础配置 Secure=true，dev 覆盖为 false。
- 本地默认 dev、端口 9333、回环地址；dev 默认真实 OSS，但本轮的普通测试切换 local，不等于本轮进行了真实 OSS 验收。

## 3. 本轮关联的业务调用链

以下是代码中现存调用顺序，不表示全部路径均在本轮通过端到端测试。

### 3.1 获取挑战

`GET /api/v1/admin/session/challenge`

1. `AdminSessionController.challenge` 取 `request.getRemoteAddr()` 作为客户端标识，设置 `Cache-Control: no-store`。
2. `AdminSessionServiceImpl.issueChallenge` → `LoginChallengeServiceImpl.issueChallenge`。
3. `LoginThrottleServiceImpl.acquireChallengePermit` → `FixedWindowRateLimiter` → `RedissonFixedWindowRateLimiter` → `RedisKeys.rateLimit` → `RATE_ACQUIRE`：递增计数并在首次写入时设置 TTL。
4. `DistributedLockService.execute` → `RedissonDistributedLockService` → 应用级签发锁。
5. 锁内 `ExpiringStringMap.size` → `MAP_SIZE`：清理过期成员并检查 128 个有效挑战上限。
6. 生成 RSA-2048 密钥对和随机 challengeId；计算客户端摘要。
7. `ExpiringStringMap.put` → `MAP_PUT`：保存客户端摘要 + PKCS#8 私钥 Base64，登记到期时间。
8. 返回 challengeId、公钥、算法标签、到期时间；`finally` 中检查锁归属后释放。

### 3.2 提交站长登录

`POST /api/v1/admin/session`

1. Controller 校验 `LoginRequest`，传递 Origin 和客户端标识。
2. `AdminSessionServiceImpl.login` 先占用登录尝试额度，再验证 Origin 白名单。
3. `LoginChallengeServiceImpl.consumePassword` → `ExpiringStringMap.take` → `MAP_TAKE`：先检查条目到期时间，再原子读取和删除。
4. 在条目已删除后校验客户端绑定、Base64、密文长度，并以 RSA-OAEP SHA-256/MGF1 SHA-256 解密；无效绑定/密文也不会恢复该挑战。
5. `UserAccountMapper` 查询 `LOWER(username)` 匹配的账号；`PasswordHasher.matches` 校验 Argon2id 哈希；检查 OWNER 与 ENABLED。
6. `StpUtil.login(accountId)` 经命名空间 DAO 建立 Redis 会话；生成 CSRF 并写入 Sa-Token Session。
7. 删除该客户端登录限流键；返回会话信息与 CSRF，Sa-Token 写会话 Cookie。

### 3.3 后续鉴权与登出

- `SecurityAutoConfiguration` 的 `SaInterceptor` 执行 Sa-Token 注解检查。
- `AdminAuthorization` 对管理路由做登录/OWNER 检查和非只读请求的 CSRF 校验，挑战、会话查询与登录入口例外放行。
- `AccountRoleProvider` → `OwnerRoleServiceImpl` → `UserAccountMapper` 查询当前账号状态/类型。当前业务角色提供器没有自己的 Redis 角色缓存；DAO 支持对象/角色类数据，不代表本轮新增了角色缓存策略。
- `DELETE /api/v1/admin/session` 经角色/CSRF 检查后调用 `StpUtil.logout`，删除或更新 Sa-Token 管理的会话数据。

### 3.4 自动配置链

Redisson Boot 4 自动配置 → `RedissonClient` → `RedisInfrastructureAutoConfiguration` → `RedisKeys` 与三个基础设施实现 → `SecurityAutoConfiguration` → 命名空间 DAO → System 业务服务。

## 4. 数据表、缓存、存储与其他基础设施

令 `P` 为应用前缀，`H(x)` 为 UTF-8 字符串的 SHA-256 小写十六进制摘要。

| 对象 | 数据/Key 形态 | 生命周期与用途 |
| --- | --- | --- |
| PostgreSQL `user_account` | 登录读取 `id/username/password_hash/type/status`；定义还含 `created_at/updated_at` | 账号、哈希、权限状态的关系型来源；本轮不新增字段、不迁移数据 |
| 挑战申请计数 String | `P:rate:auth:challenge:H(clientKey)` | 首次递增后保留 1m；超额请求也继续递增 |
| 登录尝试计数 String | `P:rate:auth:login:H(clientKey)` | 首次递增后保留 10m；登录成功主动删除 |
| 挑战数据 Hash | `P:map:auth:login:challenge:data`；field 为 challengeId | value 为客户端摘要与私钥编码；条目消费时删除 |
| 挑战到期索引 ZSet | `P:map:auth:login:challenge:expires`；member 为 challengeId，score 为 JVM 计算的 epoch 毫秒 | Lua 判定条目到期并配合 Hash 删除；两个容器有整体 TTL |
| 签发锁 | `P:lock:H(auth-login-challenge-issue)` | 由 Redisson 管理锁结构与释放；不是账号级锁 |
| Sa-Token 数据 | `P:sa-token:<Sa-Token 原始键>` | token、session 等按 Sa-Token timeout 规则保存；对象序列化沿用适配器 |
| 测试数据 | `mai-portfolio:integration:<UUID>:*` | 集成测试专用随机空间；测试结束按模式删除 |

Redis 在这些路径中存放认证运行状态，不只是可丢弃的数据库读取缓存。挑战与限流没有 DB 副本；丢失 Redis 状态可使挑战/会话失效，也可能重置限流计数。

本轮不新增或调用第三方 HTTP API，不上传/删除 OSS 对象，不使用 MQ、向量库、Agent、Caffeine。应用整体已有 PostgreSQL、OSS 和文件日志配置，但它们不是本轮 Redis 补丁新增的能力。Redis 的 RDB/AOF 配置没有在本轮核验，因此“写入 Redis”不能等同于已经验证了磁盘持久性或重启恢复。

## 5. 持久化状态变化与外部副作用

| 触发位置 | 副作用 |
| --- | --- |
| `RATE_ACQUIRE` | Redis String 自增并设置窗口 TTL；即使后续 Origin、密码、角色校验失败，已占用的额度不回滚 |
| `MAP_PUT` | 写 Hash 与 ZSet，按需要延长两个容器的 TTL |
| `MAP_TAKE` | 有效、过期或索引缺失路径均可能删除 Hash field/ZSet member；消费不可撤回 |
| `MAP_SIZE` | 清理过期成员；接口名称像查询，实际会写 Redis |
| `RLock.tryLock/unlock` | 修改 Redis 锁状态；Redisson 的内部 Lua/通知由依赖管理，不在项目自定义脚本文件中 |
| `NamespacedSaTokenDao` | set/update/delete/updateTimeout 改变 Redis 数据或 TTL；登录还改变浏览器 Cookie 和 Session 中 CSRF |
| 登录成功后的 `reset` | 删除该客户端登录计数键 |
| 登出 | 由 Sa-Token 删除/更新会话状态，写相关日志 |
| 集成测试清理 | `deleteByPattern` 删除随机命名空间下的测试键，不调用 FLUSHDB/FLUSHALL |
| 启动/测试脚本 | 修改当前进程的环境变量，启动 Maven/JVM；产生 `target`、测试报告及日志。脚本不修改系统永久环境变量 |

本轮真实 Redis 验收确实写入并清理了测试键；没有清理正常应用空间，也没有改 Redis 服务配置。交接阶段的外部变化限于 Git 本地提交和 GitHub 功能分支推送。

## 6. 事务、并发、一致性、幂等、重试、权限与异步

- **事务事实**：本轮基础设施方法没有新增 `@Transactional`、Redis MULTI 或跨 PostgreSQL/Redis 事务。单段 Lua 在 Redis 内原子执行；整个登录流程不是一个原子事务。
- **并发事实**：挑战 `size → RSA 生成 → put` 用同一签发锁串行化；消费用 Lua，无额外消费锁。锁使用固定 10s 租期，显式 leaseTime 下不能假定有 watchdog 自动续租。
- **一致性事实**：Hash 和 ZSet 在同一 Lua 中更新；`size` 清过期、`take` 校验过期。整体 TTL 延长是基于“新条目 TTL 与剩余容器 TTL”的比较。这里没有读库缓存、双删或多级缓存一致性方案。
- **一次性与幂等事实**：消费具有“最多取出一次”的语义；重复消费返回空/校验失败，不是重复请求返回同样业务结果的幂等 API。签发请求和限流递增没有业务幂等键。
- **部分失败事实**：登录已消费挑战后若 DB 查询、Argon2、会话保存或限流 reset 失败，不恢复挑战；`StpUtil.login → 写 CSRF → reset` 也没有补偿事务。客户端看到失败时不能据此确定后台是否已创建会话。
- **重试事实**：项目没有为这些业务方法增加显式重试循环。Redisson 的连接/命令重试行为来自依赖与实际配置，本轮没有独立验证。尤其不能把 `INCR` 和 `take` 当成可任意重放的读请求。
- **权限事实**：业务继续使用 Sa-Token 登录/角色判断，管理写请求另验 CSRF；普通访客入口未在本轮实现。Origin 校验发生在登录计数之后、挑战消费之前。
- **异步事实**：本轮没有新增 `@Async`、应用调度任务或 MQ 消费者；Redisson 的 I/O、连接、定时设施由客户端内部维护。新的映射实现不依赖 `RMapCache` 后台条目清理来保证统计。
- **删除事实**：消费删除、过期清理、限流 reset、登出、测试随机空间清理均真实改变 Redis；本轮没有关系型行删除或对象存储删除。

## 7. 使用的抽象、中间件与模式

| 使用项 | 具体位置与作用 | 边界 |
| --- | --- | --- |
| Service 接口/实现与三层调用 | System Controller → Service/impl → Mapper | 本轮只调整基础设施引用，不重构全部业务分层 |
| 策略/端口接口 | `FixedWindowRateLimiter`、`ExpiringStringMap`、`DistributedLockService` | Redisson 是生产实现；内存/同步替身用于测试 |
| Spring Boot Starter 与条件自动配置 | Redis/Security Starter 的 `@AutoConfiguration`、条件 Bean 和配置属性 | 属于依赖组件，不是 Blog/Agent 业务 Starter |
| 适配器扩展 | `NamespacedSaTokenDao extends SaTokenDaoForRedisson` | 命名空间和 Redis 3.2 保留 TTL 的字符串更新由项目覆盖，其余序列化继承依赖 |
| 统一 Key 生成入口 | `RedisKeys` | 当前同时承载通用格式与 System 登录命名空间常量 |
| Lua 原子命令组合 | `RedisLuaScripts` | 5 段项目脚本；不是所有 Redisson 内部脚本的镜像 |
| 固定窗口限流 | `RATE_ACQUIRE` + TTL | 没有滑动窗口、令牌桶或全局公平调度 |
| Hash + 到期索引 | `MAP_PUT/MAP_TAKE/MAP_SIZE` | 提供逐条过期语义，但引入两种结构及清理逻辑 |
| 租期型分布式互斥 | `RLock` | 没有 fencing token，不能自动防止租约过期后的旧执行者继续写 |

## 8. 当时为何这样选择：区分依据类型

下表是当时依据的陈述，不是对设计正确性的证明。同一选择可能同时有多个来源；空缺处表示没有找到对应要求。

| 选择 | Spec / Task 明确要求（含已标注的用户追加要求） | 当前技术约束 | Coding Agent 的设计判断 | 为未来扩展预留 |
| --- | --- | --- | --- | --- |
| Redis/Redisson 独立 Starter | T074/T076；用户明确要 Redis + Redisson | Boot 4、Sa-Token 1.45、用户旧 Redis 服务端兼容条件 | 用单一 Starter 隔离连接/锁/限流实现 | Agent 后续可消费已存在的 Redis 基础能力；本轮不加 Caffeine |
| Hash + ZSet + Lua 替代 RMapCache | T076 要有效数量与逐条 TTL；没有规定必须采用这套结构 | 先前 `RMapCache.size()` 不能作为严格“未过期挑战数”的依据；目标 Redis 3.2 不具备较新 Hash 字段过期能力 | 以 ZSet 保存每条到期时间，并在消费/统计时原子清理 | 复用通用字符串映射接口；当前主要调用方仍只有挑战服务 |
| Key 与 Lua 分别集中一个 Java 文件 | 用户直接要求；现有 Key 统一规范 | 多个 Starter 要共用 Key/TTL 更新脚本 | 用类型化方法隐藏拼接，Java 文本块保留脚本，写参数注释 | 新 Key/脚本可继续加入，但单文件增长和业务耦合需要复核 |
| Sa-Token 数据增加应用前缀 | T076 明确要求命名空间隔离 | Redis 可能共享实例/数据库 | 扩展原 DAO，保留同一适配器的对象序列化 | 支持不同应用前缀；不等于已支持不同环境的滚动迁移 |
| String 更新用 Lua 保留 TTL | T076 兼容 Redis 3.2、会话 TTL 验证 | Redis 3.2 不能使用 SET KEEPTTL | PTTL → SET → 必要时 PEXPIRE 放进同段 Lua | 这是兼容实现，未新增另一套会话模型 |
| 签发阶段使用全局分布式锁 | T076 要锁能力与多实例挑战；没有指定锁粒度或租期 | 多步容量检查与 RSA 生成不在同一段 Lua | 用 500ms 等待、10s 固定租期覆盖临界区 | 预期多实例，尚未多 JVM 验证；更复杂的 reservation/fencing 没有实现 |
| 普通回归替身 + 显式真实 Redis 验收 | T076 明确分开普通回归和真实验收 | 本机服务端口/凭据依赖 `.env`，CI 未搭建 | 普通测试排除 Redisson 自动配置；集成测试随机 Key 空间 | 可用于将来的独立 CI 步骤；没有创建 CI 工作流 |
| 脚本清理陈旧 Redis 变量 | 用户要求本地依赖且配置由 `.env` 提供；没有单独 Spec 编号 | PowerShell 子进程会继承旧变量 | 让本次 `.env` 缺省值覆盖先前 Redis 密码/database 等状态 | 本轮仅收敛 Redis 变量，未统一治理脚本中全部 OSS/其他变量 |

## 9. 风险最高、值得人工重点检查的实现

本节区分“源码事实”和“需要验证的风险判断”，不把尚未测试的情形写成已证实故障。

1. **映射存储格式的升级过渡**。事实：`473d090` 使用 `P:map:<namespace>` 的 RMapCache，本轮改为 `:data` 与 `:expires` 两个键；没有兼容读取或迁移旧映射。风险判断：新旧实例混跑时，某实例签发的挑战可能无法被另一种实现消费。旧键如何到期/清理也未专门验收。限流、锁、Sa-Token 的现有前缀格式保持，但不能笼统宣称所有 Redis 数据格式都保持不变。
2. **锁租期与容量上限**。事实：RSA 生成在全局锁内，固定租期 10s，无 fencing。风险判断：长 GC、CPU 阻塞或慢 Redis 导致租期过期后，旧执行者可能继续写，128 上限不应被当成已经证明的硬上限；全局串行生成还会影响吞吐。
3. **非幂等 Redis 命令与不确定结果**。事实：限流 INCR、挑战消费、会话创建均改变状态；应用没有补偿或业务重试标识。风险判断：脚本已经执行但响应丢失时，重试可能重复计数或丢失消费结果，登录流程可出现部分成功。
4. **私钥与客户端绑定**。事实：Redis value 保存私钥 Base64，没有额外应用层加密；以 remoteAddr 摘要绑定；错误客户端也会先消费该挑战。风险判断：Redis 访问/备份泄露涉及敏感私钥；HTTP 下公钥也无法自行保证来源真实性；反向代理/NAT 可能让很多访客共享限流/绑定主体。错误客户端抢先消费还可能形成拒绝服务路径。
5. **时间、TTL 与清理规模**。事实：条目到期时间使用各 JVM 时钟；ZSet 清理一次取出全部过期成员并 `unpack` 删除；TTL 仅检查 Duration 正/负，没有保证 `toMillis()` 至少为 1。风险判断：时钟偏差、极短 TTL、溢出、大批量过期或新条目较短 TTL 的交错都值得检查。Lua 原子性不能覆盖应用时钟一致性，也不代表运行时错误前的写入会回滚。
6. **Redis 部署范围**。事实：真实测试是单机 Redis 3.2.100；两个映射键没有共同 `{hash-tag}`。风险判断：不能直接推断 Redis Cluster 可运行同段多 Key Lua；主从切换、AOF/RDB、TLS、ACL 与内存淘汰行为没有验证。
7. **集中文件与模块方向**。事实：通用 Redis Starter 的 `RedisKeys` 包含 `auth:login` 等 System 场景常量。设计争议：这满足用户当前单文件管理要求，但基础设施知道业务命名空间；业务增多后是否继续集中、按业务注册或迁回业务 define 需要由维护者判断。
8. **继承式 Sa-Token DAO 的覆盖范围**。事实：字符串 get/set/update/delete/timeout/search 被覆盖，对象/Session 部分继承；真实测试验证两类对象的回读，没有逐一检查所有 DAO 方法、异常 timeout 值或多个前缀隔离。风险判断：依赖升级时可能出现未覆盖的方法、序列化或 TTL 契约变化。
9. **验收与运行配置差异**。事实：本机 Redis 监听 16379，验收前 `.env` 是 6379；本轮临时指定 16379，未修改 `.env`。普通最终回归又跳过了有外部凭据条件的测试。风险判断：新维护者照脚本直接运行可能连错端口，不能把 BUILD SUCCESS 当成数据库、OSS、完整登录流程全部通过。

## 10. 可能复杂度偏高的设计与可选方案

这是 Coding Agent 对已有选择的保留意见，均未在交接阶段实施。

| 当前选择 | 可能的复杂度/争议 | 可选方案及差异 |
| --- | --- | --- |
| 三个通用接口 + 实现 + 条件装配 | 当前主要服务一个站长登录场景，抽象维护面大于直接命令调用 | 场景专用 Redis Repository/组件；会降低抽象数量，但改变用户已确认的基础设施边界 |
| Hash + ZSet + 三段脚本 | 需要同时理解成员 TTL、容器 TTL、清理和消费 | 单挑战 String + Redis 3.2 兼容 GET/DEL Lua，另设计容量统计；或继续 RMapCache 并接受统计语义差异。前者并不免费解决严格容量问题，后者与 T076 的有效数量约束有冲突 |
| 全局签发锁包住 RSA 生成 | 安全边界依赖租期，热点串行临界区较长 | Redis 原子预约容量后在锁外生成并有失败释放；会引入预约状态/超时补偿。也可取消硬总量限制，仅按客户端限流，但这改变需求 |
| 一次性 RSA 密钥对 + 私钥缓存 | HTTP 联调可隐藏请求体中的密码，但多了生成、缓存、消费、解密及错误处理 | HTTPS 下简化登录协议；是否采用 SHA-256 变更需先对齐已存在的需求差异，不能仅替换哈希函数当成同等安全方案 |
| DAO 继承覆盖 | 必须追踪 Sa-Token 适配器内部行为 | 组合适配或新的窄存储接口；可能减少隐式继承依赖，也可能需要覆盖更多契约与测试 |
| 所有 Key/脚本集中单文件 | 当下便于定位，未来可能膨胀；脚本放 Java 文本块减少 Lua 工具独立检查便利性 | 按业务 define、按脚本 `.lua` 资源、统一注册目录等；与用户当前“各一个文件”的要求有取舍 |
| 很早使用共享 Redis + 分布式锁 | 单机站长场景运维与故障面上升 | 单机内存或本地同步机制较少依赖，但这是用户主动要求升级 Redis 后未选择的方案；不应偷偷回滚 |

## 11. 已验证行为与未验证边界

### 11.1 已执行的证据

- 2026-09-30，本轮先完成相关 14 模块 Maven 回归；真实集成测试在没有开关时跳过。
- 真实 Redis 验收改用本机 `127.0.0.1:16379`，Redisson 客户端 4.7.0，服务端为本地 Redis-x64-3.2.100；`RedissonIntegrationTest` 2 项和 `NamespacedSaTokenDaoIntegrationTest` 2 项均实际运行，失败/错误/跳过均为 0。
- 完整 34 模块 Maven `test` 构建最终成功；最初完整测试遇到 Windows 临时目录清理 AccessDenied，随后以所需权限重跑通过。该权限问题没有通过改测试代码绕过。
- 最终普通回归的 Launch 测试报告为 30 项，其中 22 项条件跳过，失败/错误为 0；Redis 两个真实集成类在该最后一次普通运行中也跳过。跳过的真实 DB/接口/OSS 路径不能算本轮实测通过。
- Git 暂存差异格式检查通过；本轮没有为集中常量编造等价实现测试，也没有在交接阶段重跑测试。
- Maven 报告位于各模块 `target/surefire-reports`，不入 Git。后一次普通运行覆盖了前一次真实集成报告，当前文件中的 skipped 不能用于否认此前终端已观测的 4 项真实通过，也不能当作可随 Git 克隆获得的证据。

### 11.2 关键测试覆盖

| 测试类 | 已覆盖行为 | 测试性质/限制 |
| --- | --- | --- |
| `RedissonIntegrationTest` | 同客户端第 3 次限流失败；挑战有效取出一次、再次为空；真实锁执行；100ms 条目在 150ms 后不计数且不能消费 | 同一测试进程、单节点；极短 TTL 测试含真实 sleep，有时间抖动可能 |
| `NamespacedSaTokenDaoIntegrationTest` | String 保存/读回；update 保留 TTL；search 返回去前缀结果；updateTimeout；delete；List 和 SaSession 读回 | 真 Spring 自动配置 + 真 Redis；不是完整浏览器登录，也未遍历适配器全部方法 |
| `RedissonDistributedLockServiceTest` | 正常执行并释放当前线程拥有的锁；等待失败时不执行业务 | Mockito，不证明真实锁竞争、租约过期或故障转移 |
| `NamespacedSaTokenDaoTest` | String 读/写使用预期应用前缀 | Mockito，不覆盖真实序列化和 timeout 全部边界 |
| `LoginChallengeServiceImplTest` | RSA 加/解密闭环；二次消费失败；客户端不匹配后原客户端也不能复用 | 内存映射不实现 TTL、锁立即执行；类名/方法名不能代替跨进程证据 |
| `LoginThrottleServiceImplTest` | 配额耗尽抛业务异常；不同主体委托；成功后 reset；挑战申请限流失败 | 计数 mock，不覆盖 Redis 固定窗口边界与并发 |
| `BootContextSmokeTest`、架构/模块接线测试 | Boot 4 上下文与所选库装配；管理路径之外的 `@SaCheckRole` 对匿名访问生效；模块/实体规则 | DB 不可用地址、Flyway/bootstrap 关闭、测试替身；不是实际部署运行 |

### 11.3 尚未验证

- 两个 JVM 同时签发/消费同一挑战、竞争容量边界、进程重启后的消费。
- 10s 锁租期溢出、线程中断、长 GC、锁归属变化及 fencing 缺失的影响。
- Redis 响应丢失、断网、写入成功后超时、客户端重试导致的非幂等行为。
- 时钟不一致、极短/极长/溢出 TTL、同成员覆盖写、新短条目不会影响长条目的交错测试。
- Hash/ZSet 键缺失或类型损坏、Lua 执行中异常、大规模过期成员的清理耗时。
- 旧 RMapCache 与新键格式混合运行、旧挑战迁移、旧键回收。
- Redis 精确 3.2.1 版本、Cluster、主从切换、RDB/AOF 恢复、淘汰策略。
- 多前缀/多 database 隔离、全部 Sa-Token 对象及 Session 更新/超时契约、角色变更与会话缓存的交互。
- 真实 PostgreSQL + 真实 Redis + 浏览器完整登录/登出/CSRF；普通访客注册、登录及博客导出。
- Redis 中私钥的存储保护、HTTP 主动攻击、反向代理共享 IP 与登录成功 reset 并发竞态。
- 从全新干净 checkout 运行本轮所有测试；当前用户 POM 改动和本机 Maven settings 的影响。

复现入口是 `backend/test-redis.ps1`，但它会按 `.env` 覆盖进程里的 Redis 地址；仅在 shell 先设置 16379 并不能确保脚本保留该值。运行前先核对真实服务端口与 `.env`。普通回归在 `backend` 目录用 Java 21 执行 `mvn test`；若要完整数据库验收，还需满足测试声明的环境变量条件，不应盲目向生产服务注入凭据。

IDEA 2024 自带 JUnit runner 的 `getMethodParameterTypes()` 二进制兼容问题仍存在于使用 JUnit 6 的原生运行方式；本轮未降级 JUnit，已验证的是 Maven 运行路径。

## 12. 定向阅读的核心文件清单

以下链接指向交接时工作区绝对路径。异机 checkout 时把根目录替换成当地仓库路径。标注“本轮”表示在 `b5a07a1` 修改/新增；“上下文”表示帮助理解调用链，不能当成本轮新代码。

### 12.1 Spec / Task / 规范

- [spec.md](F:/code/ai/codex/projects/mai-portfolio/specs/001-personal-portfolio/spec.md)：FR-018/038/045 及整体边界。
- [tasks.md](F:/code/ai/codex/projects/mai-portfolio/specs/001-personal-portfolio/tasks.md)：重点 T002、T010/T011、T069、T074、T076。
- [plan.md](F:/code/ai/codex/projects/mai-portfolio/specs/001-personal-portfolio/plan.md)：存在旧 Redis 范围描述。
- [springboot-nuxt-code-style.md](F:/code/ai/codex/projects/mai-portfolio/specs/001-personal-portfolio/springboot-nuxt-code-style.md)：模块边界、三层、常量、Redis 规范。
- [coding-standards-template.md](F:/code/ai/codex/projects/mai-portfolio/specs/001-personal-portfolio/coding-standards-template.md)：Key 定义的 `define/cache` 目录约定。

### 12.2 本轮生产代码与运行脚本

- [RedisKeys.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/main/java/dev/amai/portfolio/redis/define/cache/RedisKeys.java)：新增；格式、摘要、业务命名空间、Sa-Token 包装/解包。
- [RedisLuaScripts.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/main/java/dev/amai/portfolio/redis/define/RedisLuaScripts.java)：新增；五段脚本及参数契约。
- [RedissonExpiringStringMap.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/main/java/dev/amai/portfolio/redis/store/RedissonExpiringStringMap.java)：本轮替换映射实现。
- [RedissonFixedWindowRateLimiter.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/main/java/dev/amai/portfolio/redis/rate/RedissonFixedWindowRateLimiter.java)：本轮集中调用。
- [RedissonDistributedLockService.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/main/java/dev/amai/portfolio/redis/lock/RedissonDistributedLockService.java)：本轮统一 Key；阅读固定租期与 finally。
- [RedisInfrastructureAutoConfiguration.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/main/java/dev/amai/portfolio/redis/autoconfigure/RedisInfrastructureAutoConfiguration.java)：本轮 Bean 接线。
- [NamespacedSaTokenDao.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-security/src/main/java/dev/amai/portfolio/security/session/NamespacedSaTokenDao.java)：本轮集中 Key/Lua，阅读继承边界与 TTL。
- [SecurityAutoConfiguration.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-security/src/main/java/dev/amai/portfolio/security/SecurityAutoConfiguration.java)：本轮调整装配顺序。
- [LoginChallengeServiceImpl.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/main/java/dev/amai/portfolio/system/service/impl/LoginChallengeServiceImpl.java)：本轮引用集中常量；容量、RSA、绑定、消费调用链。
- [LoginThrottleServiceImpl.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/main/java/dev/amai/portfolio/system/service/impl/LoginThrottleServiceImpl.java)：本轮引用集中常量；业务限流入口。
- [run-local.ps1](F:/code/ai/codex/projects/mai-portfolio/backend/run-local.ps1)：本轮 Redis 环境变量处理。
- [test-redis.ps1](F:/code/ai/codex/projects/mai-portfolio/backend/test-redis.ps1)：本轮真实验收入口扩展与默认变量处理。

### 12.3 调用链、抽象与配置上下文

- [AdminSessionController.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/main/java/dev/amai/portfolio/system/controller/AdminSessionController.java)：HTTP 入口、客户端标识、ApiLog/角色注解。
- [AdminSessionServiceImpl.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/main/java/dev/amai/portfolio/system/service/impl/AdminSessionServiceImpl.java)：Origin、DB/密码、Session、CSRF、reset 的顺序。
- [AdminAuthorization.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/main/java/dev/amai/portfolio/system/auth/AdminAuthorization.java)：路由例外与管理写请求校验。
- [AccountRoleProvider.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/main/java/dev/amai/portfolio/system/auth/AccountRoleProvider.java) 与 [OwnerRoleServiceImpl.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/main/java/dev/amai/portfolio/system/service/impl/OwnerRoleServiceImpl.java)：当前 DB 角色来源。
- [ExpiringStringMap.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/main/java/dev/amai/portfolio/redis/ExpiringStringMap.java)、[FixedWindowRateLimiter.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/main/java/dev/amai/portfolio/redis/FixedWindowRateLimiter.java)、[DistributedLockService.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-common/src/main/java/dev/amai/portfolio/common/lock/DistributedLockService.java)：三个基础设施契约。
- [RedisInfrastructureProperties.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/main/java/dev/amai/portfolio/redis/autoconfigure/RedisInfrastructureProperties.java)、[SecurityProperties.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/main/java/dev/amai/portfolio/system/config/SecurityProperties.java)：配置属性与校验范围。
- [application.yml](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-launch/src/main/resources/application.yml)、[application-dev.yaml](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-launch/src/main/resources/application-dev.yaml)：运行配置；本轮未修改。
- [dependencies/pom.xml](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-dependencies/pom.xml)、[Redis Starter POM](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/pom.xml)、[Security Starter POM](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-security/pom.xml)：版本和依赖方向。
- [V1__portfolio_and_document_core.sql](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-launch/src/main/resources/db/postgresql/V1__portfolio_and_document_core.sql)：只定向阅读 `user_account`；本轮没有 SQL 改动。

### 12.4 测试文件

- [RedissonIntegrationTest.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/test/java/dev/amai/portfolio/redis/integration/RedissonIntegrationTest.java)：本轮扩展。
- [NamespacedSaTokenDaoIntegrationTest.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-security/src/test/java/dev/amai/portfolio/security/session/NamespacedSaTokenDaoIntegrationTest.java)：本轮新增。
- [RedissonDistributedLockServiceTest.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-redis/src/test/java/dev/amai/portfolio/redis/lock/RedissonDistributedLockServiceTest.java)、[NamespacedSaTokenDaoTest.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-framework/mai-portfolio-spring-boot-starter-security/src/test/java/dev/amai/portfolio/security/session/NamespacedSaTokenDaoTest.java)：本轮接线更新。
- [LoginChallengeServiceImplTest.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/test/java/dev/amai/portfolio/system/service/impl/LoginChallengeServiceImplTest.java)、[LoginThrottleServiceImplTest.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-modules/mai-portfolio-module-system/mai-portfolio-module-system-service/src/test/java/dev/amai/portfolio/system/service/impl/LoginThrottleServiceImplTest.java)：业务单元覆盖及替身限制。
- [RedisTestConfiguration.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-launch/src/test/java/dev/amai/portfolio/RedisTestConfiguration.java)、[测试 application.yaml](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-launch/src/test/resources/application.yaml)：普通回归替身和 Redisson 自动配置排除。
- [BootContextSmokeTest.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-launch/src/test/java/dev/amai/portfolio/BootContextSmokeTest.java)、[AdminSecurityTest.java](F:/code/ai/codex/projects/mai-portfolio/backend/mai-portfolio-launch/src/test/java/dev/amai/portfolio/AdminSecurityTest.java)：区分实际执行的装配测试和有凭据条件的接口测试。

本文写入、提交与推送完成后，Coding Agent 停止本轮工作。后续独立分析不以本文的判断替代源码和测试证据，也不授权自动新增功能或重构。
