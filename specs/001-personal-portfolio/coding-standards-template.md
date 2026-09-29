# Java Spring Boot 多模块项目编码规范与项目模板

> 提炼自 orion-visor (Spring Boot 2.7 + Vue 3 + MyBatis-Plus + MySQL/Redis/InfluxDB)。
> 本文档面向**开发人员与 AI Agent**,既是编码规范,也是项目脚手架说明书。

---

## 一、项目目录结构

```
project-root/
├── project-dependencies/          # [1] 版本总账本 (BOM)
│   └── pom.xml                    #     dependencyManagement, 只声明版本不写代码
├── project-common/                # [2] 公共工具箱
│   └── src/main/java/
│       └── org/example/project/common/
│           ├── constant/          #    常量与枚举
│           ├── entity/            #    通用实体 (如 PushUser)
│           ├── exception/         #    异常类
│           ├── utils/             #    工具类
│           └── security/          #    安全相关接口
├── project-framework/             # [3] 地基组件 (Spring Boot Starters)
│   ├── project-spring-boot-starter-web/
│   ├── project-spring-boot-starter-mybatis/
│   ├── project-spring-boot-starter-redis/
│   └── ...                        #    每个 starter 都是独立 jar
├── project-modules/               # [4] 业务模块
│   ├── project-module-common/     #    模块间共享
│   ├── project-module-system/     #    系统域 (用户/权限/字典)
│   ├── project-module-business/   #    业务域
│   │   ├── project-module-business-provider/   # 📜 契约
│   │   └── project-module-business-service/    # 🔨 实现
│   └── project-module-monitor/    #    监控域
├── project-launch/                # [5] 启动入口
│   ├── src/main/resources/
│   │   ├── application.yaml
│   │   ├── application-dev.yaml
│   │   └── application-prod.yaml
│   └── src/main/java/.../LaunchApplication.java
├── project-ui/                    # [6] 前端
│   └── src/
│       ├── api/                   #    接口调用
│       ├── views/                 #    页面
│       ├── router/                #    路由
│       └── store/                 #    状态
├── sql/                           # [7] 数据库脚本
│   ├── init-1-schema-databases.sql
│   ├── init-2-schema-tables.sql
│   └── init-3-data.sql
└── docs/                          # [8] 文档
```

### 依赖方向 (单向向下)

```
launch → modules → framework → common → dependencies
                    ↑
                   UI
```

**规则:**
- 上层依赖下层,**下层绝不允许反向依赖上层**
- `dependencies` 只做版本管理,不放代码
- `common` 不放任何框架相关代码(纯粹的 Java 工具)
- `framework` 每个 starter 可独立插拔,只依赖 `common`
- `modules` 的每个域拆成 `-provider` 和 `-service` 两半(见第二节)

---

## 二、业务模块的 Provider / Service 双层模式

### 为什么拆

| 层 | 比喻 | 职责 |
|---|---|---|
| `-provider` | **合同** | 对外数据模型 (DTO/Entity/Enum) + 跨模块 API 接口 |
| `-service` | **施工队** | Controller / Service / DAO / Handler + API 接口的实现 |

**其他模块只能依赖 `-provider`,绝不能碰 `-service`。**

### 包结构约定

```
org.example.project.module.<域>.
├── api                         ← 跨模块接口 (provider)
├── api.impl                    ← 接口实现   (service)
├── controller                  ← HTTP 入口
├── service                     ← 业务逻辑
│   └── impl                    ← 实现类
├── dao                         ← 数据访问
├── handler                     ← 专项处理器
│   └── <feature>               ← 按功能分组
├── entity
│   ├── domain                  ← 数据库实体 (DO)
│   ├── dto                     ← 传输对象
│   ├── request                 ← 请求对象
│   └── vo                      ← 视图对象
├── enums                       ← 枚举
├── define
│   ├── cache                   ← 缓存 key 定义
│   └── operator                ← 操作日志类型定义
└── convert                     ← MapStruct 转换器
```

### 跨模块调用示例

```java
// ---- provider 侧 (project-module-business-provider) ----
// api/HostApi.java
public interface HostApi {
    HostDTO selectByAgentKey(String agentKey);
    List<HostDTO> selectByIdList(List<Long> idList);
}

// ---- 调用方 (project-module-monitor-service) ----
@Service
public class MonitorServiceImpl {
    @Resource
    private HostApi hostApi;  // ← 只依赖接口,不碰实现

    public void doSomething(String agentKey) {
        HostDTO host = hostApi.selectByAgentKey(agentKey);
        // ...
    }
}

// ---- service 侧 (project-module-business-service) ----
// api/impl/HostApiImpl.java
@Service
public class HostApiImpl implements HostApi {
    @Resource
    private HostDAO hostDAO;

    @Override
    public HostDTO selectByAgentKey(String agentKey) {
        return hostDAO.selectByAgentKey(agentKey);
    }
}
```

**红线:** 禁止跨模块直接调 DAO、禁止跨模块直接读另一张表。

---

## 三、框架组件 (Starter) 规范

### 命名

```
project-spring-boot-starter-<功能>
```

### 每个 Starter 的标准结构

```
project-spring-boot-starter-xxx/
├── pom.xml
└── src/main/java/org/example/project/framework/xxx/
    ├── configuration/
    │   ├── config/
    │   │   └── XxxConfig.java            # @ConfigurationProperties("project.xxx")
    │   └── OrionXxxAutoConfiguration.java  # @AutoConfiguration
    ├── core/
    │   ├── utils/
    │   │   └── XxxUtils.java             # 静态工具
    │   └── enums/
    └── src/main/resources/
        └── META-INF/
            └── additional-spring-configuration-metadata.json  # IDE 提示
```

### 自动配置类模板

```java
@Lazy(false)
@AutoConfiguration
@AutoConfigureOrder(AutoConfigureOrderConst.FRAMEWORK_XXX)
@ConditionalOnProperty(value = "project.xxx.enabled", havingValue = "true")
@EnableConfigurationProperties(XxxConfig.class)
public class OrionXxxAutoConfiguration {

    @Bean
    public XxxService xxxService(XxxConfig config) {
        return new XxxService(config);
    }
}
```

### 配置属性类模板

```java
@Data
@ConfigurationProperties("project.xxx")
public class XxxConfig {
    private String url;
    private String username;
    private String password;
}
```

**规则:**
- 每个 Starter **自包含**,启动失败应在 Bean 创建时快速报错 (fail-fast)
- 工具类提供 `static` 方法,内部持有 client 引用,由自动配置注入
- 默认禁用 (`@ConditionalOnProperty`),需要时显式开启

---

## 四、后端分层代码风格

### 4.1 Controller 层

```java
@RestWrapper       // 统一响应包装
@RestController
@RequestMapping("/asset/host")
@Validated
public class HostController {

    @Resource
    private HostService hostService;

    @DemoDisableApi
    @OperatorLog(HostOperatorType.CREATE)
    @PostMapping("/create")
    @PreAuthorize("@ss.hasPermission('asset:host:create')")
    public Long createHost(@Validated @RequestBody HostCreateRequest request) {
        return hostService.createHost(request);
    }
}
```

**控制器注解栈 (从上到下):**
1. `@RestWrapper` / `@RestController` — 类级
2. `@RequestMapping` — 类级
3. `@Validated` — 类级
4. `@DemoDisableApi` — 方法级 (演示模式禁用)
5. `@OperatorLog` — 方法级 (操作日志)
6. `@PreAuthorize` — 方法级 (权限)
7. `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` — 方法级
8. `@Validated` + `@RequestBody` — 参数级

### 4.2 Service 层

```java
public interface HostService {
    Long createHost(HostCreateRequest request);
}

@Service
public class HostServiceImpl implements HostService {

    @Resource
    private HostDAO hostDAO;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createHost(HostCreateRequest request) {
        // 1. 参数校验
        // 2. 业务逻辑
        // 3. 持久化
        // 4. 清理/刷新缓存
        return hostId;
    }
}
```

**规则:**
- **接口 + 实现分离** (哪怕只有一个实现)
- 接口放 `service/`,实现放 `service/impl/`
- 只在需要事务的方法上加 `@Transactional`
- `@Resource` 注入 (不用 `@Autowired`)

### 4.3 DAO 层

```java
// 实体
@Data
@TableName("host")
public class HostDO {
    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("agent_key")
    private String agentKey;
}

// DAO 接口
@Mapper
public interface HostDAO extends BaseMapper<HostDO> {
    // MyBatis-Plus 自动提供 CRUD
    // 复杂查询写在这里或 Mapper XML 中
}
```

**规则:**
- DAO 是 MyBatis-Plus 的 `BaseMapper` 子接口
- 实体用 `@TableName` / `@TableField` 映射
- 逻辑删除使用 MyBatis-Plus 内置 `@TableLogic`
- 复杂 SQL 放 `resources/mapper/XxxMapper.xml`,命名与 DAO 对应

### 4.4 Handler 层

> 当一个功能逻辑复杂、自成体系时,从 Service 中拆出来,放到 `handler/` 包下。

```java
// handler/<feature>/
handler/agent/install/
├── AgentInstaller.java            # 接口
├── AbstractAgentInstaller.java    # 抽象基类
├── LinuxAgentInstaller.java       # 平台实现
├── WindowsAgentInstaller.java     # 平台实现
└── model/
    └── AgentInstallParams.java    # 内部模型
```

**模式:接口 + 抽象基类 + 策略枚举**

```java
public interface AgentInstaller extends Runnable {

    void run();

    static void start(AgentInstallParams params) {
        AgentInstaller installer = HostOsTypeEnum.WINDOWS.equals(params.getOsType())
            ? new WindowsAgentInstaller(params)
            : new LinuxAgentInstaller(params);
        AssetThreadPools.AGENT_INSTALL.execute(installer);
    }
}
```

### 4.5 Entity / DTO / VO / Request 分层

| 后缀 | 全称 | 用途 | 注解 |
|---|---|---|---|
| `DO` | Data Object | 数据库实体 | `@TableName` `@TableField` |
| `DTO` | Data Transfer Object | 跨模块/跨层传输 | — |
| `VO` | View Object | 返回给前端 | `@Schema` (Swagger) |
| `Request` | — | 前端请求体 | `@Schema` + JSR-303 |

**规则:**
- DO 只在 DAO 和 Service 之间传递,**不暴露到 Controller 之外**
- DTO 可跨模块 (放在 `-provider`)
- VO 只在 Controller 返回时使用 (放在 `-service`)
- Request 只在 Controller 接收时使用 (放在 `-service`)

---

## 五、校验与异常处理

### 校验

```java
// Request 字段级校验
@NotBlank
@Size(max = 128)
@Schema(description = "主机名称")
private String name;

// Service 级 Assert
Assert.notNull(host, ErrorMessage.HOST_ABSENT);
Assert.eq(hosts.size(), idList.size(), ErrorMessage.HOST_ABSENT);
Assert.isTrue(HostTypeEnum.SSH.contains(host.getTypes()), ErrorMessage.PLEASE_CHECK_HOST_SSH);
```

### 异常

```java
// 错误消息枚举
public enum ErrorMessage implements ErrorMessageBase {
    HOST_ABSENT("host.absent", "主机不存在"),
    FILE_ABSENT("file.absent", "文件不存在 {}"),       // 支持占位符
    CONFIG_ABSENT("config.absent", "主机配置不存在");

    private final String code;
    private final String message;

    @Override
    public String getMessage(Object... params) {
        return Strings.format(this.message, params);
    }
}

// 统一异常处理
@ExceptionHandler
public HttpWrapper<?> handleException(Exception e) {
    if (e instanceof ApplicationException) {
        return HttpWrapper.error(e.getMessage());
    }
    return HttpWrapper.error(ErrorMessage.EXEC_ERROR);
}
```

### 统一响应包装

```java
// HttpWrapper = {code, message, data}
// @RestWrapper 自动包装
// Controller 直接返回 data,框架自动套上 code=200 + message="success"
```

---

## 六、缓存设计模式

### Cache Key 定义

```java
public interface XxxCacheKeyDefine {

    CacheKeyDefine X_LIST = new CacheKeyBuilder()
            .key("project:xxx:list:{}")        // {} 为占位符
            .desc("描述 ${paramName}")
            .type(XxxDTO.class)
            .struct(RedisCacheStruct.HASH)      // 或 STRING / LIST
            .timeout(8, TimeUnit.HOURS)
            .build();

    CacheKeyDefine X_DETAIL = new CacheKeyBuilder()
            .key("project:xxx:{}")
            .desc("描述 ${id}")
            .type(XxxDTO.class)
            .struct(RedisCacheStruct.STRING)
            .timeout(8, TimeUnit.HOURS)
            .build();
}
```

### 缓存使用模式

```java
// 读: cache-aside
String key = XxxCacheKeyDefine.X_DETAIL.format(id);
XxxDTO data = RedisStrings.getJson(key, XxxCacheKeyDefine.X_DETAIL);
if (data == null) {
    data = loadFromDB(id);
    RedisStrings.setJson(key, XxxCacheKeyDefine.X_DETAIL, data);
}
return data;

// 写: 更新 DB 后删除缓存
// 注意: 如果写 DB 和删缓存不在同一个事务,要考虑一致性
RedisStrings.delete(key);
```

**规则:**
- 所有 Redis key 集中定义在 `define/cache/` 包
- 使用一个有规律的全局前缀 (如 `project:` 或 `v2:`)
- TTL 必须有合理值 (不做永不过期的 key)
- 更新数据时先写 DB,再清缓存

---

## 七、配置规范

### application.yaml 分层

```yaml
# application.yaml —— 所有环境共享
spring:
  application:
    name: project-name
  profiles:
    active: dev          # 默认 dev

# application-dev.yaml —— 开发环境覆盖
spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/...

# application-prod.yaml —— 生产环境覆盖
spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:mysql}:${MYSQL_PORT:3306}/...
```

### 属性命名约定

| 层次 | 前缀 | 示例 |
|---|---|---|
| 应用级 | `project.*` | `project.api.prefix` / `project.demo` |
| 框架组件 | `project.<组件>.*` | `project.redis.database` / `project.influxdb.url` |
| 业务配置 | `app.*` | `app.executors.metricsExecutor.core-pool-size` |
| 第三方 | 使用原前缀 | `spring.datasource.*` / `mybatis-plus.*` / `springdoc.*` |

### 敏感信息

- 开发环境允许写在 `application-dev.yaml` 中 (确保不提交到公共仓库)
- 生产环境**必须使用环境变量**:`${MYSQL_PASSWORD:}` (不设默认值)
- 或用独立的 `application-local.yaml` + `.gitignore`

---

## 八、前端项目结构

```
project-ui/
└── src/
    ├── api/                    # 接口层
    │   ├── interceptor.ts      # 全局拦截器
    │   ├── module-a/
    │   │   └── resource-a.ts   # 按后端模块组织
    │   └── module-b/
    ├── views/                  # 页面
    │   └── module-a/
    │       ├── index.vue       # 页面入口
    │       ├── components/     # 页面级组件
    │       ├── types/          # TS 类型 & 常量 & 校验规则
    │       │   ├── const.ts
    │       │   ├── form.rules.ts
    │       │   └── use-xxx.ts  # composable
    │       └── hooks/          # 通用 hooks
    ├── router/
    │   └── routes/
    │       └── modules/        # 按模块组织路由
    ├── store/
    │   └── modules/            # Pinia stores
    ├── hooks/                  # 全局 composables
    ├── types/                  # 全局类型
    ├── utils/                  # 工具函数
    └── assets/                 # 静态资源
```

### 前端约定

```ts
// API 调用
export function getXxxPage(request: XxxQueryRequest) {
  return axios.post<DataGrid<XxxQueryResponse>>('/xxx/query', request);
}

// 字典驱动
const { toOptions, getDictValue } = useDictStore();

// 权限按钮
<a-button v-permission="['module:resource:action']"> ... </a-button>

// 表单校验
import formRules from '../types/form.rules';
```

---

## 九、SQL 脚本管理

```
sql/
├── init-1-schema-databases.sql      # 建库
├── init-2-schema-tables.sql        # 建表 (所有 DDL)
├── init-3-schema-quartz.sql        # 框架表 (Quartz 等)
├── init-4-data.sql                 # 种子数据 (字典、菜单、管理员账号)
└── upgrade/                        # 增量升级脚本
    └── upgrade-v2.x.x-to-v2.y.y.sql
```

### 通用约定

- 全部使用 `InnoDB` 引擎 + `utf8mb4` 字符集
- 逻辑删除字段统一用 `deleted` (`tinyint, 0=正常, 1=删除`)
- 时间字段: `create_time` / `update_time`,默认 `CURRENT_TIMESTAMP`
- 审计字段: `creator` / `updater` (`varchar(64)`)
- **JSON 字段**用于灵活配置 (如 `channel_config`、`monitor_config`)

---

## 十、AI Agent 使用指南

当 AI Agent 在此规范下工作时,请遵循:

### 添加新功能时

1. **确定放在哪一层**:
   - 通用工具 → `project-common`
   - 可插拔组件 → `project-framework/project-spring-boot-starter-xxx`
   - 业务功能 → `project-modules/project-module-<域>`
2. **确定 Provider/Service 边界**:
   - 需要被其他模块调用的 → 接口放 `-provider/api/`,实现放 `-service/api/impl/`
   - 仅模块内用的 → 直接放 `-service`
3. **遵循现有包命名**,不要自创新的顶层包

### 添加 Controller 时

```java
// 按这个骨架来
@RestWrapper
@RestController
@RequestMapping("/<module>/<resource>")
@Validated
public class XxxController {

    @Resource private XxxService xxxService;

    @PostMapping("/action")
    @OperatorLog(XxxOperatorType.ACTION)
    @PreAuthorize("@ss.hasPermission('module:resource:action')")
    public ResultType action(@Validated @RequestBody XxxRequest request) {
        return xxxService.action(request);
    }
}
```

### 添加配置时

- Starter 用 `@ConfigurationProperties("project.xxx")` + `@EnableConfigurationProperties`
- 业务配置用 `@Value` 或 Spring 的 `@ConfigurationProperties`
- **配置 key 一定要写进 `additional-spring-configuration-metadata.json`**

### 添加 SQL 时

- 新表入 `init-2-schema-tables.sql`
- 种子数据入 `init-4-data.sql`
- **永远不修改已发布到生产环境的 SQL 文件** —— 用独立的 `upgrade/` 脚本

### 关键不要做的事

- ❌ 跨模块直接调 DAO
- ❌ 在 `-provider` 里放 `@Service` / `@Component` 注解的实现类
- ❌ 在 Controller 里写业务逻辑 (Controller 只做参数接收 + 调用 Service)
- ❌ 硬编码配置值 (使用常量类 `Const` 或配置属性)
- ❌ 永不过期的缓存 key
- ❌ 修改已经发布到生产环境的 SQL 脚本