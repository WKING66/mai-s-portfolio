# 注册路由与匿名鉴权边界修复

日期：2026-10-08。功能分支 `feature/auth-registration-route-fix`，父分支 `feature/nuxt-ui-toast-feedback`，起点 `4e77220`。提交后停止供维护者审核，不合并、不推送。

## 业务规则与根因

普通注册允许匿名调用，不自动登录、不赋予 OWNER；必须继续满足来源、HTTPS、凭据结构、新密码政策和独立注册限流。登录/查询/注销管理会话，与注册是同一认证领域内的不同能力。

工作区将 AuthController 的类级前缀改为 `/api/v1/auth/session`，注册方法使用 `/register`，前端随之改为 `/api/v1/auth/session/register`；AuthenticationInterceptor 的匿名豁免仍只匹配 `/api/v1/auth/register`。结果是正式注册路径不再有处理方法，新嵌套路径却要求已有登录与 CSRF。

修复前已复现：完整应用匿名 POST `/api/v1/auth/register` 得到 404，前端注册请求合同检查得到错误的嵌套路径。未以扩大豁免范围或增加旧接口兼容分支掩盖问题。

稳定核心是注册权限和会话语义；本次实际变化点是 Controller 类级映射与完整鉴权路径必须一致。复用现有 AuthConstants 与 AuthenticationInterceptor，不新增 Controller/Service、路由注册器或权限策略框架。

## 功能正确性

- Controller 的领域前缀为 `/api/v1/auth`，会话相对路径为 `/session`，注册相对路径为 `/register`。
- AuthConstants 由同一前缀和相对路由组合完整路径；Controller 与鉴权层分别使用相对/完整常量，避免独立手写导致漂移。
- 前端注册 API 恢复 `/api/v1/auth/register`；登录、会话查询和注销仍使用 `/api/v1/auth/session`。
- 保留精确的 POST 注册豁免，嵌套路径与相邻写路径仍要求登录；HTTPS、来源、限流和业务校验继续由原能力执行。
- 正式路径的匿名非法字段请求进入真实认证服务并返回专用参数错误 400，而非路由 404、登录 401 或 CSRF 403；不要求数据库可用才执行字段校验。
- OpenAPI 显示独立注册入口，无 Cookie 登录要求，不保留 `/auth/session/register`。

## 设计与职责边界

AuthConstants 是 Java 路由定义的事实来源；Controller 只映射和委派，AuthenticationInterceptor 只执行通用请求安全边界，注册规则仍由 AuthService 及已有组件承担。前端注册地址只有一处常量；前端请求、完整应用与 OpenAPI 测试固定对外合同，不再只拿生产常量与自身比较。

维护者已有的 Controller import 格式调整留在工作区，不混入本轮提交。前端 registration.ts 的原未提交改动仅为错误路径，本轮修复后与父提交一致，因此该文件没有新增 Git 差异；最终前端代码与本轮接口合同一致。

## 当前支持的扩展点与未提前设计的内容

已有会话和注册映射共用认证领域前缀；调整前缀或相对路由时，完整鉴权路径自动由常量组合得出。新认证动作须声明自己的业务权限，不能自动继承匿名豁免。

不引入通用路由 Registry、动态匿名规则、OpenAPI 客户端生成器、多版本兼容层或新的认证业务抽象。本轮没有确认需要这些能力。

## Maintainability Review

| 检查 | 结论 |
|---|---|
| 增加同类认证动作需要修改多少位置 | 后端路由定义、Controller 入口及真实权限规则；有前端调用时补其地址和合同验证，无须复制认证 Service |
| 替换外部依赖是否影响业务 | 本轮没有新增外部依赖或厂商耦合 |
| 是否重复业务判断 | 沿用原注册、认证与密码政策，未新增相同规则 |
| 是否为了当前测试硬编码生产分支 | 无；没有测试用户名、特殊路径放行或兼容 fallback |
| Source of Truth 是否明确 | Java 路由由 AuthConstants 组合；前端常量通过真实对外合同验证 |
| 失败路径是否完整 | 正式注册路径的字段错误、嵌套/相邻路径拒绝、原 HTTPS/Origin/限流/冲突规则均由本轮受影响测试覆盖 |
| 配置、异常、日志 | 未修改配置与异常体系，未新增日志或输出凭据 |
| 下一次需求是否迫使复制 | 同一认证域仍共用现有核心能力，角色及匿名权限不成为新 Service 分类 |

## 技术债务与验证边界

当前工作区另有未完成的登录页编辑：导入 `../constants/route` 的 `Route`，但文件尚未导出该成员，导致 typecheck 返回 TS2305。本轮不改动这份维护者在途代码，也不将其问题算作路由已修复后的全面验收通过。

为验证提交候选，将父提交的前端导出到忽略目录 `.local-backups/registration-route-validation/frontend`，只覆盖本轮注册常量与请求测试，复用现有 node_modules。独立目录先由 Nuxt 生成类型，再运行测试和构建；未临时替换原登录页或路由文件来伪造工作区通过。

验证期间工作区又出现存储/Asset 的在途修改。本轮未编辑或提交这些文件；后端也从父提交导出，只覆盖路由修复与受影响测试，单独复验提交候选。不将注册修复的结论扩大为并行编辑已经通过验收。

数据库/Redis 使用现有测试隔离设施；未执行真实账号注册、数据库迁移或 OSS 写入，未重启已有服务。

## 测试结果

- 后端 Java 21：31 模块 package 成功；受影响的 5 个测试类共 49 项通过，零失败、零错误、零跳过。包括 AuthenticationInterceptorTest 9、AuthContractTest 5、RegistrationServiceTest 26、BootContextSmokeTest 6、OpenApiContractTest 3。
- 独立后端提交候选再次完成同样 49 项定向回归与 31 模块 package，全部成功；未重复执行无关的全量后端测试。
- 当前工作区前端 Node 24：20 个文件、228 项测试通过；生产 build 通过；typecheck 因上述在途 Route 导出缺失而失败。
- 独立提交候选前端：20 个文件、228 项通过，Nuxt typecheck 与生产 build 均通过。测试初次在全新导出目录缺少 Nuxt 生成类型；先执行类型生成/检查后复验通过，未修改生产代码或测试规避问题。
- 完整应用回归使用真实 Controller、认证拦截器、认证服务、字段校验与异常处理；正式注册路径无需已有会话，嵌套与相邻写路径未被放行。
- 前端请求测试固定 `/api/v1/auth/register`，并验证加密 payload、不自动登录及失败不重试；OpenAPI 验证同一路径、匿名属性、主要错误响应与错误嵌套路径不存在。

命令与日志：后端使用离线 Maven `-pl mai-portfolio-launch -am package -Dtest=AuthenticationInterceptorTest,AuthContractTest,RegistrationServiceTest,BootContextSmokeTest,OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false`，并指定工作区测试临时目录。修复前后日志、前端工作区与提交候选日志位于 `.local-backups/registration-route-*.log`。
