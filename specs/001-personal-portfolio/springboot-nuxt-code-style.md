# Spring Boot + Nuxt.js 项目代码编码规范

> 适用范围：Spring Boot 后端、Nuxt.js / Vue / TypeScript 前端。  
> 本规范用于约束 AI 与开发人员生成、修改、重构代码时的编码行为。
>
> 核心要求：**不得以“能运行”为最低标准，必须同时满足职责清晰、命名明确、无魔法值、异常可控、日志有效、接口文档完整、类型安全、风格统一、代码可维护。**

---

# 1. 通用编码原则

所有代码必须遵循以下原则：

1. 单一职责：一个类、方法、组件只承担明确职责。
2. 高内聚、低耦合：相关逻辑集中，不相关逻辑分离。
3. KISS：优先简单、直接、清晰的实现。
4. DRY：不得复制粘贴相同业务逻辑。
5. YAGNI：不得为不存在的未来需求过度设计。
6. 显式优于隐式：重要行为、状态、错误必须明确表达。
7. 可读性优先：禁止为了炫技使用复杂写法。
8. 不得破坏已有代码风格和项目约定。
9. 修改已有代码前，必须先理解现有实现和调用关系。
10. 不得为了“兜底”而吞异常、静默失败或返回假成功。
11. 不得为了“兼容未来情况”添加没有需求依据的 fallback。
12. 不得因为“只是小功能”就跳过异常、日志、参数校验、接口文档和类型定义。

---

# 2. 后端代码分层规范

后端代码必须按照职责调用，不得跨层调用。

推荐调用关系：

```text
Controller
    ↓
Service
    ↓
Repository / Mapper / Infrastructure
```

## 2.1 Controller

Controller 只允许负责：

- 接收 HTTP 请求
- 参数绑定
- 参数校验
- 调用 Service
- 返回统一响应
- 添加 Swagger / OpenAPI 描述

Controller 禁止：

- 直接调用 Mapper
- 直接调用 Repository
- 直接写 SQL
- 直接操作 Redis
- 直接发送 MQ
- 直接调用第三方接口
- 编写复杂业务逻辑
- 编写事务逻辑
- 大量对象转换
- 到处 try-catch 业务异常

错误示例：

```java
@PostMapping("/users")
public ApiResponse<Void> create(@RequestBody CreateUserRequest request) {
    if (request.getName() == null) {
        return ApiResponse.fail("name不能为空");
    }

    UserEntity user = new UserEntity();
    user.setName(request.getName());

    userMapper.insert(user);

    redisTemplate.opsForValue().set(
            "user:" + user.getId(),
            user,
            86400,
            TimeUnit.SECONDS
    );

    return ApiResponse.success();
}
```

正确职责：

```java
@PostMapping
@Operation(summary = "创建用户")
public ApiResponse<UserResponse> create(
        @Valid @RequestBody CreateUserRequest request) {

    return ApiResponse.success(userService.create(request));
}
```

---

## 2.2 大型业务模块与 Spring Boot Starter 边界

博客、Agent 等大型业务不得继续堆放在同一个应用源码模块中，统一采用**同进程模块化单体**，不得因此擅自拆成微服务。

模块职责固定如下：

- `mai-portfolio-dependencies`：BOM，只负责统一内部模块和第三方依赖版本。
- `mai-portfolio-common`：只存放已经确认可跨业务域复用且不依赖框架的公共能力、通用工具和通用模型，例如统一响应、分布式锁抽象、JSON 转换、通用 Util、分页 Request/Vo。某个 Starter 专属的接口、模型、异常和工具必须归该 Starter，不得为了“看起来通用”放入 common，也不得预造尚无调用方的公共类。
- `mai-portfolio-framework`：只放可复用依赖组件的 Spring Boot Starter；当前按 Web、参数校验、接口日志、Sa-Token 安全、MyBatis、数据源、Flyway、OpenAPI/Knife4j、对象存储拆分，不得放 Blog/Agent 业务。
- `mai-portfolio-module-*-provider`：只公开所属业务域允许其他模块使用的 API、DTO 和枚举，不得包含 Mapper、DO 或实现类。
- `mai-portfolio-module-*-service`：拥有所属业务域的 Controller、Service 接口/impl、Mapper、DO 和资源，并保持三层架构。
- `mai-portfolio-launch`：唯一可执行应用，只负责启动、环境配置和业务模块装配，不承载业务实现。

强制依赖规则：

1. 依赖方向固定为 `launch → modules → framework → common → dependencies`，禁止反向依赖。
2. Blog 与 Agent 是业务模块，不得做成 Spring Boot Starter；两者禁止引用对方的 Mapper、DO、Service 实现或内部 DTO。
3. 跨模块协作必须只依赖能力所属模块的 provider，并通过窄接口与稳定 DTO 完成；不得为了省事开放整个内部模型。
4. 文档可见性、发布状态和资源授权由 Blog 模块判定；Agent 只能消费已经授权的知识读取接口，不以“已建立索引”代替授权。
5. 基础设施 Starter 使用 `@AutoConfiguration` 和 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 显式注册；禁止扫描整个 `dev.amai.portfolio` 根包。
6. LangChain、LangGraph、Milvus 等 Agent 专属依赖只能存在于 Agent service，不得传染 Blog、common 或 launch。
7. 每个业务模块必须拥有自己的单元测试与集成测试；聚合工程还须运行全量回归。
8. Provider 可以先保留边界说明，但不得伪造已完成的业务 API；业务实现随对应任务逐项进入 service。
9. 一个 Starter 只拥有一种基础设施职责；业务 service 只依赖内部 Starter，不得再次直接声明该 Starter 已封装的第三方运行依赖。
10. 项目自定义自动配置 Bean 必须提供合理的条件装配和覆盖点；仅聚合官方依赖的 Starter 不得重复实现官方自动配置。
11. 接口日志采用 `@ApiLog` 显式启用，禁止按 `RestController` 全局兜底切入；新增标注时必须先确认字段脱敏、内容截断和文件流省略策略。

---

# 3. Service 编码规范

Service 负责：

- 业务规则
- 业务流程编排
- 数据一致性
- 事务控制
- Repository / Mapper 协调
- 缓存、MQ、第三方能力的业务编排

Service 不得成为万能类。

禁止：

```text
UserServiceImpl.java
2000 行
```

如果一个 Service 同时承担：

- 登录
- 注册
- 用户资料
- 密码修改
- 权限处理
- 邮件发送

应根据职责拆分。

方法命名必须体现业务动作，例如：

```java
createUser()
updateProfile()
changePassword()
disableUser()
publishArticle()
```

禁止使用含义模糊的名称：

```java
handle()
process()
doSomething()
execute()
deal()
```

除非上下文已经非常明确。

---

# 4. Repository / Mapper 规范

Repository / Mapper 只负责数据访问。

禁止包含：

- 业务判断
- 权限判断
- HTTP 调用
- Redis 操作
- MQ 操作
- 复杂流程编排

数据库查询必须避免：

- `SELECT *`
- N+1 查询
- 循环中频繁查询数据库
- 无条件查询超大数据集
- 无分页返回大量记录

---

# 5. Entity / Request / Vo 分离

数据库实体不得直接作为 Controller 的请求或响应对象。

禁止：

```java
@PostMapping
public UserEntity create(@RequestBody UserEntity user)
```

禁止：

```java
@GetMapping("/{id}")
public UserEntity detail(@PathVariable Long id)
```

应使用：

```text
CreateUserRequest
UpdateUserRequest
UserQueryRequest
UserVo
UserDetailVo
```

禁止创建一个万能：

```text
UserDTO
```

然后所有接口全部复用。

HTTP 入参统一放在 `entity/request`，并以 `Request` 结尾；返回前端的数据统一放在 `entity/vo`，并以 `Vo` 结尾。禁止使用 `Response`、`DTO`、`Data` 或 `Envelope` 作为接口实体后缀，也禁止把返回类型放进 request 目录。仅供 OpenAPI 展开泛型响应的具体模型同样属于返回模型，必须放在 `entity/vo` 并使用 `Vo` 后缀。

内部基础设施命令（例如对象存储写入参数）应放在所属模块的领域或 SPI 包中；不得为了后缀一致把非 HTTP 类型塞进 `entity/request`。

---

# 6. 命名规范

## 6.1 Java 类命名

使用大驼峰：

```text
UserController
UserService
UserRepository
CreateUserRequest
UserVo
UserStatus
```

禁止：

```text
UserManager2
UserUtilImpl2
DataHandler
CommonService
BaseBusiness
```

## 6.2 方法命名

使用小驼峰，使用动词开头：

```java
createUser()
findUserById()
updatePassword()
validatePermission()
buildResponse()
```

## 6.3 布尔值命名

推荐：

```java
isEnabled
hasPermission
canDelete
shouldRetry
```

禁止：

```java
flag
statusFlag
checkFlag
```

## 6.4 集合命名

使用复数或明确集合语义：

```java
users
userIds
articleList
permissionSet
```

禁止：

```java
data
list
temp
obj
```

除非作用域极小且语义完全明确。

---

# 7. 魔法值规范

任何具有业务含义的数字和字符串不得直接散落在代码中。

错误：

```java
if (user.getStatus() == 2) {
}
```

正确：

```java
if (user.getStatus() == UserStatus.DISABLED) {
}
```

错误：

```java
Thread.sleep(3000);
```

正确：

```java
private static final Duration RETRY_DELAY = Duration.ofSeconds(3);
```

错误：

```java
redisTemplate.opsForValue().set(key, value, 86400, TimeUnit.SECONDS);
```

正确：

```java
private static final Duration USER_CACHE_TTL = Duration.ofDays(1);
```

或放入配置。

---

# 8. 常量规范

常量必须：

- 使用 `static final`
- 使用全大写下划线命名
- 表达业务语义

例如：

```java
private static final int MAX_RETRY_COUNT = 3;

private static final Duration TOKEN_EXPIRE_DURATION =
        Duration.ofHours(2);
```

禁止：

```java
private static final int THREE = 3;
```

常量必须表达“为什么是这个值”，而不是把数字机械搬到常量类。

---

# 9. 枚举规范

有限状态必须优先使用枚举。

例如：

```java
public enum UserStatus {
    ACTIVE,
    DISABLED
}
```

禁止在代码中到处写：

```java
status == 0
status == 1
status == 2
```

如果数据库使用数值存储，也应在 Java 层转换为明确枚举。

---

# 10. 异常规范

禁止直接：

```java
throw new RuntimeException("失败");
```

禁止：

```java
throw new Exception("error");
```

应使用明确异常类型：

```java
throw new ResourceNotFoundException(UserErrorCode.USER_NOT_FOUND);
```

异常必须表达业务语义。

推荐：

```text
BusinessException
ResourceNotFoundException
ForbiddenException
ConflictException
ExternalServiceException
```

---

# 11. 禁止吞异常

绝对禁止：

```java
try {
    ...
} catch (Exception ignored) {
}
```

绝对禁止：

```java
try {
    ...
} catch (Exception e) {
    return null;
}
```

绝对禁止：

```java
try {
    ...
} catch (Exception e) {
    log.warn("执行失败");
}
return true;
```

任何 catch 都必须说明：

1. 为什么需要捕获？
2. 捕获后如何恢复？
3. 是否应该重新抛出？
4. 是否影响事务？
5. 是否需要记录日志？

无法回答时，不应 catch。

---

# 12. 全局异常处理规范

项目应统一使用：

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
}
```

统一处理：

- 参数校验异常
- 业务异常
- 权限异常
- 数据冲突
- 外部服务异常
- 未知系统异常

Controller 禁止到处：

```java
try {
    service.xxx();
} catch (Exception e) {
    return ApiResponse.fail("操作失败");
}
```

---

# 13. 未知异常兜底规范

允许在全局异常处理器中统一兜底未知异常：

```java
@ExceptionHandler(Exception.class)
public ApiResponse<Void> handleUnexpectedException(Exception e) {
    log.error("Unexpected server error", e);
    return ApiResponse.fail(CommonErrorCode.INTERNAL_SERVER_ERROR);
}
```

未知异常必须：

- 记录 ERROR 日志
- 保留完整异常堆栈
- 对外隐藏内部实现细节
- 返回统一错误结构

禁止把真实异常消息直接返回给前端：

```java
return ApiResponse.fail(e.getMessage());
```

---

# 14. 日志规范

日志必须记录“有价值的信息”，不得写流水账。

禁止：

```java
log.info("进入方法");
log.info("开始处理");
log.info("执行成功");
log.info("执行结束");
```

推荐：

```java
log.info(
    "User created, userId={}, operatorId={}",
    userId,
    operatorId
);
```

推荐：

```java
log.warn(
    "Login failed, username={}, reason={}",
    username,
    reason
);
```

推荐：

```java
log.error(
    "Failed to upload object, objectKey={}",
    objectKey,
    exception
);
```

---

# 15. 日志级别规范

## ERROR

用于：

- 系统异常
- 数据库异常
- Redis 异常
- MQ 异常
- 第三方服务异常
- 文件系统异常
- 未预期异常

## WARN

用于：

- 可预期业务异常
- 非法状态
- 重复操作
- 降级行为
- 请求被拒绝

## INFO

用于重要业务事件：

- 创建
- 删除
- 状态变化
- 管理员操作
- 任务完成

## DEBUG

用于开发调试和内部细节。

---

# 16. 日志敏感信息规范

禁止记录：

- 密码
- Token
- Refresh Token
- Cookie
- Authorization Header
- Secret
- AccessKey
- 私钥
- 完整身份证信息
- 完整银行卡信息

禁止：

```java
log.info("Login request={}", request);
```

如果 request 中含密码。

---

# 17. 参数校验规范

Controller 请求参数必须优先使用 Bean Validation。

例如：

```java
public class CreateUserRequest {

    @NotBlank
    @Size(max = 32)
    private String username;

    @Email
    private String email;
}
```

Controller：

```java
public ApiResponse<UserResponse> create(
        @Valid @RequestBody CreateUserRequest request) {
}
```

简单格式校验不得全部手写：

```java
if (request.getUsername() == null) {
}
```

复杂业务校验应放 Service。

---

# 18. Swagger / OpenAPI 编码规范

所有对外 API 必须完整补充 OpenAPI 注解。

Controller：

```java
@Tag(name = "用户管理")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
}
```

接口：

```java
@Operation(
    summary = "创建用户",
    description = "创建新的系统用户"
)
```

参数：

```java
@Parameter(
    description = "用户 ID",
    example = "10001"
)
```

DTO：

```java
@Schema(description = "创建用户请求")
public class CreateUserRequest {

    @Schema(
        description = "用户名",
        example = "alice",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String username;
}
```

禁止只写：

```java
@Operation(summary = "xxx")
```

然后 DTO、枚举、参数全部没有描述。

---

# 19. Swagger 文档完整性

接口文档必须至少表达：

- API 用途
- 参数含义
- 是否必填
- 示例值
- 枚举含义
- 返回结构
- 分页结构
- 主要业务异常

新增接口时，Swagger 不得后补。

---

# 20. 事务规范

事务原则上放 Service 层。

禁止 Controller 开事务。

禁止：

```java
@Transactional
public List<UserResponse> listUsers() {
}
```

无脑给所有方法添加事务。

纯查询根据实际情况使用：

```java
@Transactional(readOnly = true)
```

或不加事务。

必须注意：

- Spring AOP 代理
- 自调用问题
- 异常是否触发回滚
- 事务范围
- 外部接口调用是否应该放在长事务中

---

# 21. null 规范

必须明确哪些值允许为 null。

禁止：

```java
if (x != null)
```

在项目中无意义扩散。

禁止使用 null 表达多个不同业务状态。

Repository 查询不存在时，可合理使用：

```java
Optional<UserEntity>
```

Entity 字段和方法参数不要滥用 Optional。

---

# 22. Optional 使用规范

推荐：

```java
userRepository.findById(id)
        .orElseThrow(() ->
                new ResourceNotFoundException(
                        UserErrorCode.USER_NOT_FOUND
                )
        );
```

禁止：

```java
public void update(Optional<User> user)
```

禁止：

```java
private Optional<String> username;
```

---

# 23. 方法设计规范

一个方法应只完成一个明确目标。

当一个方法同时包含：

```text
参数校验
数据库查询
权限判断
计算
数据库保存
清缓存
发 MQ
发邮件
```

并且代码过长，应拆分。

推荐：

```java
validateRequest(request);
UserEntity user = buildUser(request);
userRepository.save(user);
publishUserCreatedEvent(user);
```

但禁止形式主义拆分。

例如：

```java
private String getName(User user) {
    return user.getName();
}
```

如果没有额外语义，不应存在。

---

# 24. 条件判断规范

避免多层嵌套。

错误：

```java
if (user != null) {
    if (user.isEnabled()) {
        if (hasPermission(user)) {
            ...
        }
    }
}
```

推荐：

```java
if (user == null) {
    throw ...;
}

if (!user.isEnabled()) {
    throw ...;
}

if (!hasPermission(user)) {
    throw ...;
}
```

优先使用：

- Guard Clause
- Early Return
- 明确异常

---

# 25. 循环规范

禁止在循环内重复执行高成本操作。

错误：

```java
for (Long userId : userIds) {
    UserEntity user = userMapper.selectById(userId);
}
```

应优先批量查询。

禁止在循环中：

- 重复数据库查询
- 重复远程请求
- 重复构建昂贵对象
- 重复解析相同配置

---

# 26. Stream API 规范

Stream 用于提高表达能力，不是炫技。

推荐：

```java
List<Long> userIds = users.stream()
        .map(UserEntity::getId)
        .toList();
```

禁止为了“一行代码”写复杂 Stream：

```java
list.stream()
    .filter(...)
    .map(...)
    .flatMap(...)
    .collect(...)
    .entrySet()
    .stream()
    ...
```

当 Stream 明显降低可读性时，应使用普通循环。

---

# 27. Lombok 规范

可以合理使用：

```java
@Getter
@Setter
@RequiredArgsConstructor
@Builder
```

禁止 Entity 无脑：

```java
@Data
```

特别是涉及：

- ORM Entity
- 双向关联
- equals/hashCode
- toString
- 敏感字段

时必须谨慎。

优先使用构造器注入：

```java
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
}
```

禁止字段注入：

```java
@Autowired
private UserRepository userRepository;
```

---

# 28. 注释规范

注释主要解释：

> 为什么这样做。

错误：

```java
// 获取用户
UserEntity user = userRepository.findById(id);
```

有价值：

```java
// 第三方接口不支持批量请求，因此这里必须逐条提交。
```

复杂规则、特殊兼容、非显然行为必须说明原因。

禁止大量无意义 JavaDoc。

---

# 29. TODO / FIXME 规范

禁止留下没有说明的：

```text
TODO
FIXME
临时处理
以后优化
```

允许：

```java
// TODO: 第三方 API v2 上线后删除旧协议兼容逻辑。
```

AI 不得通过 TODO 逃避实现当前需求。

---

# 30. 工具类规范

禁止创建万能：

```text
CommonUtils
BaseUtils
AppUtils
MyUtils
```

工具类必须职责明确：

```text
HashUtils
DateTimeUtils
JsonUtils
```

如果逻辑包含业务含义，不应放入 Utils。

---

# 31. 配置值规范

以下内容禁止硬编码：

- URL
- Port
- Timeout
- Retry Count
- Cache TTL
- MQ Topic
- Redis Namespace
- 上传限制
- 第三方服务配置

应放入：

```yaml
application.yml
application-dev.yml
application-prod.yml
```

并通过：

```java
@ConfigurationProperties
```

映射。

优先：

```java
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {
}
```

避免大量：

```java
@Value("${xxx}")
```

散落在各类中。

---

# 32. Redis 编码规范

Redis Key 必须统一生成。

禁止：

```java
"user:" + userId
```

散落在业务代码中。

推荐：

```java
UserCacheKey.of(userId)
```

或：

```java
RedisKeys.userProfile(userId)
```

TTL 必须使用常量或配置。

缓存异常不能被无脑忽略。

如果缓存故障允许降级，必须：

- 明确记录日志
- 明确降级行为
- 不得伪装成缓存正常

---

# 33. MQ 编码规范

消息类必须语义明确：

```text
UserRegisteredEvent
ArticlePublishedEvent
OrderPaidEvent
```

禁止：

```text
MessageDTO
MqData
DataMessage
```

Producer 和 Consumer 必须明确处理：

- 消息类型
- 序列化
- 异常
- 重试
- 幂等
- 日志

禁止 Consumer：

```java
try {
    process(message);
} catch (Exception ignored) {
}
```

---

# 34. 第三方接口调用规范

第三方客户端必须集中封装。

推荐：

```text
client/
integration/
gateway/
```

禁止 Service 到处直接：

```java
WebClient.create()
```

禁止每个调用点自行定义：

- Timeout
- Header
- Retry
- Error Mapping

调用失败必须转换为项目明确异常。

---

# 35. Nuxt / Vue 文件职责规范

页面组件主要负责：

- 页面布局
- 页面级状态
- 页面级数据加载
- 组合业务组件

禁止一个 `.vue` 文件同时承担：

- API 请求封装
- 复杂业务计算
- 公共工具方法
- 大量状态管理
- 大量重复 UI
- 全局逻辑

出现明显多职责时必须拆分。

---

# 36. Vue 组件命名规范

组件使用大驼峰：

```text
UserProfileCard.vue
ArticleEditor.vue
LoginForm.vue
CommentList.vue
```

禁止：

```text
Common.vue
Component1.vue
DataComponent.vue
MyComp.vue
Test.vue
```

组件名必须体现职责。

---

# 37. Vue Props 规范

Props 必须定义明确类型。

推荐：

```ts
interface Props {
  userId: number
  readonly?: boolean
}

const props = defineProps<Props>()
```

禁止：

```ts
defineProps<any>()
```

禁止接收巨大万能对象：

```ts
props: {
  data: Object
}
```

除非该对象有明确类型。

---

# 38. Vue Emits 规范

事件必须定义类型：

```ts
const emit = defineEmits<{
  save: [id: number]
  cancel: []
}>()
```

事件名称必须表达语义：

```text
save
delete
submit
cancel
change
```

禁止：

```text
event1
handle
do
click2
```

---

# 39. Composable 规范

Composable 用于复用完整逻辑。

例如：

```text
useAuth()
usePagination()
useArticleEditor()
useUpload()
```

禁止为了封装一两行代码创建无意义 composable。

Composable 不得偷偷包含与名称无关的副作用。

---

# 40. 前端 API 调用规范

禁止页面中到处直接：

```ts
$fetch('/api/users')
```

应统一封装 API。

例如：

```text
api/
├── auth.ts
├── user.ts
└── article.ts
```

```ts
export function getUser(id: number) {
  return apiClient<UserResponse>(`/users/${id}`)
}
```

统一处理：

- Base URL
- Token
- Header
- 错误转换
- 请求 ID
- 通用响应结构

---

# 41. TypeScript 类型规范

禁止无理由使用：

```ts
any
```

未知外部数据优先：

```ts
unknown
```

然后进行类型收窄。

错误：

```ts
function handle(data: any) {
}
```

推荐：

```ts
function handle(data: UserResponse) {
}
```

---

# 42. TypeScript 类型断言规范

禁止使用类型断言掩盖问题：

```ts
const user = data as User
```

如果数据来源不可信，应进行校验。

禁止：

```ts
foo!.bar!.baz!
```

大量非空断言。

非空断言必须有确定依据。

---

# 43. 前端枚举和常量规范

禁止：

```ts
if (status === 2) {
}
```

推荐：

```ts
enum UserStatus {
  Active = 1,
  Disabled = 2
}
```

禁止散落：

```ts
'/api/users'
'ADMIN'
'ACTIVE'
86400
```

具有业务意义的值必须统一管理。

---

# 44. 前端异常处理规范

禁止：

```ts
try {
  await api()
} catch (e) {
}
```

禁止：

```ts
catch {
  return null
}
```

接口错误必须明确：

- 是否由全局处理
- 是否需要页面提示
- 是否需要局部恢复
- 是否需要重新抛出

不要在每个页面重复相同错误处理逻辑。

---

# 45. 前端 Loading 状态规范

异步操作必须正确维护状态：

```ts
loading.value = true

try {
  await submit()
} finally {
  loading.value = false
}
```

禁止只在成功时关闭 loading。

---

# 46. 前端状态管理规范

局部状态优先局部管理。

只有确实跨页面、跨组件共享的数据才进入 Store。

禁止所有数据都塞入 Pinia。

Store 必须按领域拆分：

```text
useAuthStore
useUserStore
useArticleStore
```

禁止：

```text
useGlobalStore
useAppStore
```

存放所有业务状态。

---

# 47. 前端 watch 规范

禁止滥用：

```ts
watch(...)
```

解决本可通过 computed 或明确事件处理的问题。

优先：

```ts
computed
```

其次：

```ts
事件
```

最后才考虑：

```ts
watch
watchEffect
```

watch 中不得隐藏复杂业务副作用。

---

# 48. computed 规范

可派生状态必须优先使用 computed。

禁止：

```ts
watch(
  () => firstName.value,
  () => {
    fullName.value = `${firstName.value} ${lastName.value}`
  }
)
```

应：

```ts
const fullName = computed(
  () => `${firstName.value} ${lastName.value}`
)
```

---

# 49. CSS / 样式规范

Nuxt 页面使用 Tailwind CSS 4：布局、间距、排版、响应式与常见交互状态优先使用工具类；全站色彩和字体令牌集中定义于 `frontend/app/assets/css/main.css`，不得在不同页面复制一套魔法色值。流光、倾斜、指针跟随等难以清晰表达的视觉效果可以留在职责明确的组件 `<style scoped>` 中。不要动态拼接 Tailwind 类名，否则生产构建可能无法识别；重复出现的稳定结构再抽成业务组件或公共样式，避免无意义的长类名堆砌。

禁止大量：

```html
<div style="...">
```

禁止重复样式散落各页面。

公共样式应抽取。

组件样式默认使用：

```vue
<style scoped>
```

除非明确需要全局样式。

Class 命名必须有语义。

禁止：

```text
div1
box2
redText
temp
```

---

# 50. 重复代码规范

发现重复逻辑时：

第一次出现：可以保持局部。

第二次出现：评估是否存在稳定共性。

第三次以上：原则上应考虑抽取。

但禁止为了消灭几行重复代码而制造复杂抽象。

---

# 51. 禁止过度设计

禁止没有需求依据地引入：

- Factory
- Strategy
- AbstractFactory
- Builder
- Adapter
- EventBus
- CQRS
- DDD
- 多层 Wrapper
- BaseService
- GenericProcessor

只有当当前代码确实存在对应问题时才能引入。

---

# 52. 禁止 AI 自行“兼容”

AI 不得自行加入：

```text
旧版本兼容
未来版本兼容
备用字段
默认 fallback
未知字段兼容
重复查询兜底
失败后返回空数据
```

除非现有需求明确要求。

禁止：

```java
try {
    return newApi();
} catch (Exception e) {
    return oldApi();
}
```

没有明确需求时，这类代码属于错误。

---

# 53. 禁止 AI 擅自改动无关代码

实现一个功能时：

不得顺手：

- 重命名大量类
- 格式化整个项目
- 重构无关模块
- 修改已有公共 API
- 删除看似没用但未确认的代码
- 修改数据库字段语义
- 修改现有响应格式
- 改变异常行为

修改范围必须与当前任务相关。

---

# 54. 新增依赖规范

不得为了简单功能随意增加第三方库。

新增依赖前必须确认：

1. JDK / Spring / Vue / Nuxt 是否已有能力；
2. 项目是否已经存在同类依赖；
3. 新依赖是否值得引入。

禁止一个小工具函数引入一个大型依赖。

---

# 55. Deprecated API 规范

不得使用已废弃 API。

如果现有代码正在使用 Deprecated API：

- 新代码不得继续扩散；
- 修改相关代码时优先迁移；
- 不得为了消除 warning 而无脑替换导致行为变化。

---

# 56. 重构规范

重构必须遵循：

```text
行为不变
```

如果同时需要改行为和重构，应尽量拆开。

禁止借“重构”名义偷偷修改：

- 返回值
- 异常类型
- 数据结构
- 接口路径
- 权限逻辑
- 状态逻辑

---

# 57. 代码提交前自检

AI 每次完成代码生成或修改后，必须自行检查：

## 后端

- [ ] Controller 是否只负责请求/响应
- [ ] 是否存在 Controller 直接调用 Mapper / Repository
- [ ] Service 是否职责过多
- [ ] Entity 是否直接暴露给 API
- [ ] 是否存在魔法数字
- [ ] 是否存在魔法字符串
- [ ] 是否存在裸 RuntimeException
- [ ] 是否存在吞异常
- [ ] 是否存在无意义 fallback
- [ ] 日志级别是否合理
- [ ] 是否记录敏感信息
- [ ] 参数校验是否完整
- [ ] Swagger 是否完整
- [ ] 事务是否合理
- [ ] 是否出现 N+1 查询
- [ ] 是否存在循环数据库查询
- [ ] 是否存在硬编码配置
- [ ] 是否使用 Deprecated API

## 前端

- [ ] 是否存在大量 any
- [ ] Props / Emits 是否有类型
- [ ] API 调用是否统一封装
- [ ] 是否存在魔法值
- [ ] 是否存在吞异常
- [ ] loading 是否使用 finally 恢复
- [ ] 是否滥用 watch
- [ ] 是否存在超大 Vue 文件
- [ ] 是否将所有状态都塞入 Store
- [ ] 是否存在重复 API 错误处理
- [ ] 是否存在无意义类型断言
- [ ] 是否存在硬编码 URL
- [ ] 是否修改无关模块

---

# 58. AI 生成代码强制规则

AI 在生成代码时必须遵守：

1. 先阅读已有代码风格，再编写新代码。
2. 优先复用已有公共组件、异常、Response、Converter、工具类和封装。
3. 不得自行创造第二套相同基础设施。
4. 不得为了省事跨层调用。
5. 不得留下魔法值。
6. 不得吞异常。
7. 不得返回假成功。
8. 不得无意义 try-catch。
9. 不得遗漏日志。
10. 不得遗漏 Swagger。
11. 不得遗漏参数校验。
12. 不得滥用 any。
13. 不得生成超大 Service / Controller / Vue 文件。
14. 不得未经需求证明进行过度抽象。
15. 不得私自增加“以后可能有用”的字段、接口或兼容逻辑。
16. 不得擅自修改当前任务无关代码。
17. 修改代码后必须检查调用方是否受影响。
18. 修改公共类型、接口、枚举时必须检查所有引用。
19. 新增方法、类、字段必须使用准确业务命名。
20. 如果现有设计存在明显问题，应指出问题；不能偷偷绕过或制造更多技术债。

---

# 59. 评审红线

以下任一情况出现，代码原则上不得通过 Review：

```text
Controller 直接访问数据库
Controller 大量业务逻辑
Entity 直接作为 API 请求/响应
魔法数字大量出现
魔法字符串大量出现
catch Exception 后吞掉
catch 后返回 null
异常后返回成功
无全局异常体系
重要业务无日志
日志打印密码或 Token
Swagger 明显缺失
参数无校验
所有类都使用 @Data
字段注入 @Autowired
大量 any
页面到处直接 $fetch
一个 Vue 文件承担大量职责
一个 Service 超大且职责混乱
大量复制粘贴代码
配置硬编码
滥用 fallback
无需求依据的“未来扩展”
为了简单问题引入复杂设计模式
修改大量与任务无关代码
```

---

# 60. 最终原则

生成的代码必须满足：

```text
看得懂
找得到
改得动
测得了
出错能定位
接口有文档
职责不混乱
行为不靠猜
```

任何时候，**清晰、稳定、可维护**都优先于“少写几行代码”和“先跑起来再说”。
