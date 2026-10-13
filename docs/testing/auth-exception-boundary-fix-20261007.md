# 认证参数异常边界修正

日期：2026-10-07。属于维护者对上一轮交付的审核修正，继续使用 `feature/backend-maintainability-fixes`；父分支仍为 `feature/api-feedback-alerts`（263201a），上一轮提交 cbbe641。

## 功能正确性

登录/注册字段缺失、空白或超过协议长度，由认证服务抛出 InvalidCredentialPayloadException，返回 400 / AUTH_INVALID_CREDENTIAL_PAYLOAD。消息改为“认证请求参数不合法”，不把缺用户名或非法注册请求描述成登录密文错误。字段详情只包含约束名称与消息，不带被拒绝的值。

普通 MVC 的 MethodArgumentNotValidException 继续返回 422 / VALIDATION_FAILED。登录凭据错误仍为 401 / AUTH_INVALID_CREDENTIALS；注册用户名和解密后密码规则仍为业务 422；重名/并发唯一键冲突为 409。无法解析 JSON 或请求体缺失仍由 Web 层返回 400 / MALFORMED_REQUEST，不伪装成业务校验失败。

## 设计与职责边界

Controller 绑定请求、调用服务，不选择异常或判断字段/业务规则。DTO 保留原有必填和协议长度约束，作为字段约束的单一来源；注册用户名字符/长度政策及新密码政策仍由既有服务/组件拥有。

AuthRequestValidator 使用项目已有 Jakarta Validator 执行这些约束，在 AuthServiceImpl 的两个入口调用。没有复制 NotBlank/Size 判断，没有用路径、DTO 类型或测试账号选择响应，也没有为了改异常名手工创建 Spring 的绑定异常。AuthRequestExceptionHandler 只翻译专用异常，优先于全局 ApiException 兜底；不再捕获 MethodArgumentNotValidException。

字段校验、限流、来源检查、解密及注册政策检查先于数据库事务。账户查询/密码验证/凭据版本锁定/建会话，以及注册判重与写入仍在 TransactionTemplate 内执行，使用现有 PlatformTransactionManager。沿用 AccountProfileServiceImpl 已有的事务方式，不通过自注入或公开内部事务方法解决代理边界。

## 当前支持的扩展点

新增同类认证入口时，可为请求声明字段约束并在服务入口调用同一校验组件；异常及 HTTP 翻译无需追加路径分支。修改已有字段约束只需改对应声明。验证实现依赖现有 Jakarta Validator 接口，数据库事务依赖 Spring 的 PlatformTransactionManager，无新增自定义策略框架。

## 主动没有提前设计的未来扩展

不为登录/注册各造一套重复参数异常，不为每个错误码生成异常子类；密码错误、注册政策和冲突沿用已有业务错误码。不新增请求类型注册表、校验分组调度器、通用 Service 装饰器或异常工厂。文件上传、图片、OSS、资产框架保持本轮范围外。

## Maintainability Review

- 同类入口新增：字段声明和服务调用为明确改动面，不修改通用 Web 校验或按 URL 特判。
- 外部依赖替换：复用已有 Validator、事务管理器接口；没有把 MVC 异常引入业务服务。
- 重复规则：字段约束仅定义一处，两个服务入口执行同一组件；注册和改密密码政策不受影响。
- Source of Truth：协议字段约束为 DTO 元数据，业务政策为既有服务/组件，HTTP 翻译为对应异常处理器。
- 失败路径：null/空白/超长请求在限流、解密、数据库事务前拒绝；格式合法后的身份验证/注册政策/冲突仍区分；并发插入失败回滚。
- 配置、异常和日志：没有新配置，无密码/密文详情回显，无测试专属生产分支；使用已有 ApiException 错误码及 Logger。
- 后续复制风险：没有重复登录/注册校验器；新增字段不需要同步两套手写判断。

## 技术债务与交付边界

修复仍在隔离工作区 `F:/code/ai/codex/projects/mai-portfolio/.local-backups/fix`。原目录保留维护者的未提交修改，包括仍在整理的全局处理器；没有覆盖原目录、合并、推送或重启默认服务。因此本记录不宣称原目录运行中的应用已经切换到修复版本。

真实数据库/Redis 集成和资产相关债务继承上一轮报告，本次没有扩大授权范围。合法请求数据库事务的真实 PostgreSQL 行为仍需环境集成验证，单元测试中的事务替身不作为真实数据库验收。

## 测试结果

首轮新增完整应用请求回归发现：将字段校验简单移入 @Transactional 服务会先获取数据库连接，非法请求在数据库不可用时不能可靠返回 400。保留该回归，修正生产事务边界；没有模拟掉认证服务或数据库事务来掩盖问题。停止的仅为该轮 Surefire 测试进程，不是默认服务。

- Java 21 / 离线 Maven 专项测试成功（20:26:16）：GlobalExceptionHandlerTest 3、AuthContractTest 5、RegistrationServiceTest 26、BootContextSmokeTest 3，共 37 项执行、0 失败、0 错误、0 跳过。
- 完整应用回归使用真实认证服务、真实字段校验器、真实事务管理器及异常处理器；数据库设置为不可用测试地址、Redis 使用已有替身。缺字段请求仍能返回专用异常/400，证明未先申请数据库事务。
- 全模块 package 成功（20:27:46）；该构建复用前述已完成验证并跳过测试，未将构建中的跳过描述为测试通过。
- OCR delegate 解析了 6 项生产代码差异，逐项按解析规则和业务边界复核 6/6；其排除的 3 项测试和 2 项文档另外复核。未调用外部模型代审，未发现未处理的明显结构性问题。
- git diff --check 通过；11 项相关代码、测试及文档进入提交，配置、凭据、日志、构建产物和所有上传实现均不进入提交。

验证输出保存在原目录受忽略的 .local-backups/auth-exception-focused-fixed.log 与 auth-exception-package.log；首轮失败记录为 auth-exception-focused.log。OCR 选取、规则及复核覆盖记录为 auth-exception-ocr-preview.json、auth-exception-ocr-rules.json、auth-exception-review.json。
