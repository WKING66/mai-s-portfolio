# 后端编码规范

适用于全部后端自有 Java 源码、功能测试、POM 及相关配置。Git 与 V0–V3 验证遵循 [Git 工作流](git-workflow.md)，本文只规定编码、可读性和格式检查；共同规范由后端 Agent 维护。

## 不改变业务事实

本轮存量整改保持 API 路径、字段与响应、数据库 Schema/Flyway、鉴权授权、Document 版本、事务与补偿、OSS 签名权限及 Spring Bean 装配契约不变。不升级或删减现有依赖，不改变 Scope、模块顺序及已有插件执行阶段。需要业务修复的问题在 PR 简述，另行处理，不混入格式整改。

## Java 排版

- UTF-8、LF；Java 使用 Google Java Format 的 AOSP 风格，基础缩进四个空格，常规代码行宽由固定版本格式化器统一管理。backend/.gitattributes 固定 Java、POM 与格式配置的 LF，避免 Windows 检出后反复转换；不要手动维护第二套 IDEA 排版风格。
- if/else、for/while/do、switch、try/catch/finally 使用清晰换行和大括号，禁止把控制流程或方法体压成一行。多语句 Lambda 同样展开，长注解、参数、构造器和复杂 Stream 按格式化器排版。
- import 统一排序；不为整理 import 更改类型绑定或静态调用。第一批不启用自动改写语法、展开通配符、缩短类型名称等自动重构步骤。
- SQL、正则、文本块和长字符串首先保持实际值不变。格式化器不自动重写长字符串；跨行整理须核对字符串值和 SQL 参数绑定，不拆成无意义的碎片。
- 复杂方法只在职责明确且行为等价时局部拆分；不为了排版引入 Factory、Strategy、Registry 或万能工具类。

正确的简单方法也应展开：

```java
public boolean exists(Long documentId) {
    return documentMapper.exists(documentId);
}
```

Formatter 负责可确定的排版；补齐遗漏的大括号、语义命名、职责和必要注释仍需 Review。不能因 Formatter 通过就称这些规则已全部满足，也不通过正则替换控制流程来代替理解代码。

## 依赖与变量命名

- 所有 Service/Mapper 类型的字段、构造器参数和局部引用使用明确的 xxxService/xxxMapper：blogService、documentMapper、userAccountMapper。禁止用 blogs、versions、mapper 等集合式或泛称表示依赖。
- 实体使用单数，集合使用对应复数：documentVersion 与 documentVersions。Converter 与业务 Service、数据 Mapper 分清职责。
- SDK 与基础设施按实际类型和用途命名，例如 ossClient、redissonClient、redissonLock、saTokenSession、redisTemplate；只有实际使用 Quartz 或 XXL-Job 时才使用 quartzScheduler、xxlJobExecutor。
- 已封装的对象按抽象职责命名，例如 objectStorage；不能为了规范把存储抽象叫 ossClient。client、manager、handler、obj、data、temp 等只在极小且含义明确的上下文使用。
- 重命名同时核对构造器、Lombok 生成注入、Qualifier/Resource、按名称装配、反射及测试引用。公开 DTO/VO 字段、JSON 属性、Bean 名称和第三方 API 名称不能作为普通变量随意重命名。

## 中文业务步骤与 JavaDoc

- 复杂 Service、多步骤事务、认证权限、Document/Asset 资源生命周期方法，按实际流程写编号中文步骤，标明校验、读取/锁定、业务判断、写入/状态变更和结果投影。
- 步骤对应当前真实实现，重点解释业务意图、顺序约束和非显然条件；不机械给每行赋值编号，不重复方法名。简单委托和 Getter/Setter 不堆砌注释。
- 重要公共方法补充必要 JavaDoc：职责、关键参数约束、返回语义与主要失败边界；实现变化同步更新说明，删除过时、复制或误导注释。
- 涉及数据库与 OSS、缓存同步、文件上传、Document 版本和 Asset 绑定、Redis 锁、凭据失效时，说明真实事务边界、锁的持有/释放条件、失败路径和已有补偿。
- 数据库事务不会自动撤销外部 OSS 写入；是否已有补偿、由谁执行、何时清理，应以当前实现为准。不得为了让注释看似完整而虚构补偿或改变事务语义。
- 注释不写当前测试编号或为某个测试通过而存在的理由；业务规则保持明确的事实来源。

## 常量、异常与局部结构

- 可复用业务判断留在现有业务层，复用同一来源，不放入 Controller、DTO、Mapper 或通用工具类。
- 业务限制、状态码、资源用途及配置值复用已有常量、枚举和 Properties。语义明确的局部索引或计数初值不机械抽成常量；新增常量须有真实业务含义。
- 异常应体现当前边界与上下文，不把业务拒绝转换为校验异常，不吞异常或以默认值掩盖失败。若修复会改变既有异常契约，另行提交业务修复。
- 日志符合现有规范，不输出密码、凭据、令牌、带签名的完整 URL；不重复记录同一异常。必要日志调整先判断实际影响。
- 简化局部嵌套或提取方法时保留异常抛出顺序、短路判断、锁/事务作用域、返回顺序与外部调用次数，不借本轮进行架构重构。

## POM、XML、YAML 与 SQL

- POM/XML 四空格缩进，dependencies、dependencyManagement、plugins、executions、profiles、properties 等结构按层级换行。叶子文本可在同一行，禁止多个依赖元素挤在一行。
- POM 采用 Spotless 的 SortPom 格式步骤。空排序模板保留现有节点顺序；不排序依赖、插件、模块、属性或 executions。每批用 XML 解析核对结构、文本和元素顺序，除明确新增的格式插件配置外应保持一致。
- 运行时 YAML、Mapper XML 和 SQL 可读性整改保持解析值、占位符、参数和顺序；不重写历史 Flyway SQL。注解中的 SQL 字符串按实际值核对。
- 所有自有 POM 纳入检查，包括不继承根 POM 的 BOM；不因独立父子关系遗漏文件。

## 可重复执行的格式门禁

使用 JDK 21 和现有 Maven。以下命令从仓库根目录执行，只运行格式工具，不触发单元测试、编译、Nuxt 或全链路测试：

```text
mvn -B -ntp -N -f backend/pom.xml spotless:check
mvn -B -ntp -N -f backend/pom.xml spotless:apply
```

根 POM 的 Spotless 统一覆盖全部模块的 main/test Java、自有 tools Java 和全部 POM，排除的只有 target 生成物。插件不继承到子模块，避免反复扫描和遗漏独立 BOM。根工程 verify 阶段执行相同 check；单模块命令不能替代这个全后端门禁。

分批期间只格式化当前批次；可使用 Spotless 官方的 spotlessFiles 正则表达式参数做定向操作（不是文件通配符），例如第一批 POM：

```text
mvn -B -ntp -N -f backend/pom.xml spotless:apply "-DspotlessFiles=.*pom\.xml"
mvn -B -ntp -N -f backend/pom.xml spotless:check "-DspotlessFiles=.*pom\.xml"
```

后续完整模块批次将参数换成该模块路径。定向检查只能说明该范围通过，不能冒充全量通过；最终验收必须执行不带过滤参数的 check。不能在正式门禁中用过滤参数、ratchet、ignore、exclude 或 formatter off 隐藏存量问题。

Spotless 与 Java Formatter 固定版本在根 POM。POM 格式器版本随固定 Spotless 版本确定。工具只处理格式，不自动添加补偿、改业务逻辑或升级应用依赖。CI 可调用同一独立 check，是否配置 GitHub required checks 仍遵循维护者审批；本轮不重建 CI。

工具依据：[Spotless Maven 官方配置](https://github.com/diffplug/spotless/tree/main/plugin-maven)、[Google Java Format](https://github.com/google/google-java-format)。新增格式插件配置的原因是当前后端没有统一格式工具，不引入其他测试框架。

## 分批实施与验收

- 以完整模块划分后续批次，每批一次处理该模块的 Controller/DTO/VO、Service、Mapper、基础设施及关联功能测试，避免同一文件在不同职责批次中反复格式化。
- 第一批落地共同规范、格式工具及全部 POM 排版；之后覆盖 common/framework、System、Asset、Blog/Document、Portfolio、Agent/Launch 和剩余源码。具体批次大小依实际文件量和风险拆分。
- 仅在后端 Worktree 的 backend 实施。每批一个 backend → dev PR，前一批由维护者审核合并并同步最新 dev 后再开展下一批；不同时混入后续批次或前端修改。
- 纯格式和注释执行格式、Diff 及必要 XML 解析；依赖重命名/局部组织调整执行受影响模块编译、定向测试和必要注入验证；认证、缓存、事务、Document 状态等即使预期行为不变，也执行关联风险回归。
- 每批说明实际模块、文件数、命名/注释/排版范围、是否改变行为、实际结果和剩余范围。全量格式检查若仍失败，明确报告，不能据定向通过宣布任务完成。
- 全部批次完成后执行一次完整格式、必要后端编译和关联回归。只在全量源码规范、准确注释、依赖命名与验证均达标时宣布完成；测试通过不能替代 Maintainability Review。
- 只提交功能单元测试及必要支撑；本地联调/Windows/辅助工具用完清理，不混入本轮提交。对外项目资料仍放 resources，私人报告放 private；本规范是维护者指定的共享工程规范。
