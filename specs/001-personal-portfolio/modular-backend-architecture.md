# 后端分层模块化架构决定

- 状态：已接受
- 日期：2026-09-28
- 范围：Blog、Document、Asset 与第二阶段 Agent

## 背景

现有单模块按技术职责拆包，适合当前资料、鉴权和作品集展示功能；Blog 与 Agent 会分别引入文档版本、导入导出、媒体授权、检索编排和向量数据库等大量代码与依赖。继续放在一个应用模块会导致包规模失控、功能边界模糊，并让 Agent 专属依赖污染其他功能。目录与依赖边界参考 `orion-visor-w`，但不复制其业务代码。

## 决定

后端采用同一 JVM、同一部署单元的 Maven 模块化单体。业务域统一拆成 `provider + service`；Spring Boot Starter 只用于可复用依赖组件与基础设施自动配置，Blog、Agent 等业务域不得包装成 Starter。

| 模块 | 所有权与职责 | 禁止内容 |
| --- | --- | --- |
| `mai-portfolio-dependencies` | 统一第三方与内部模块版本的 BOM | 业务代码、运行时配置 |
| `mai-portfolio-common` | 已确认跨业务域复用且不依赖框架的公共能力、通用工具与通用模型；目前有统一响应模型、通用响应提示与分布式锁抽象 | Controller、Mapper、业务 Entity、单个 Starter 专属类型、未被实际使用的预留工具 |
| `mai-portfolio-framework/*-starter-*` | Web、校验、日志、安全、持久化、接口文档、对象存储等可复用依赖组件 | Blog、Agent 等业务规则 |
| `mai-portfolio-module-*-provider` | 所属业务域对其他模块公开的 API、DTO、枚举 | Mapper、DO、Service 实现 |
| `mai-portfolio-module-*-service` | 所属业务域的 Controller、Service 接口/impl、Mapper、DO 与资源 | 其他业务域的内部实现 |
| `mai-portfolio-launch` | 唯一启动入口、环境配置、数据库迁移和模块装配 | 业务实现、Mapper、DO |

依赖方向固定为：

```text
mai-portfolio-launch
  ├─ mai-portfolio-module-system-service → system-provider（账号认证与共享标签字典）
  ├─ mai-portfolio-module-portfolio-service → portfolio-provider + system/asset-provider
  ├─ mai-portfolio-module-asset-service  → asset-provider
  ├─ mai-portfolio-module-blog-service   → blog-provider
  ├─ mai-portfolio-module-agent-service  → agent-provider → blog-provider
  └─ mai-portfolio-framework
       ├─ starter-web
       ├─ starter-validation
       ├─ starter-api-log
       ├─ starter-security
       ├─ starter-redis（Redisson、限流、短时数据与分布式锁实现）
       ├─ starter-mybatis
       ├─ starter-datasource
       ├─ starter-flyway
       ├─ starter-openapi
       └─ starter-storage（自身拥有存储接口、模型、异常与实现）

所有模块的版本统一由 mai-portfolio-dependencies 管理。
```

Blog 与 Agent 不建立横向实现依赖。Agent 只能依赖 Blog Provider 中的已发布知识窄接口；该接口返回稳定 DTO，并由 Blog/Document Service 完成可见性、发布版本与 Asset 权限判断。索引只是派生数据，不能成为授权依据。

## 自动配置约定

Framework 当前按单一基础设施职责拆成 10 个 Starter：`web`、`validation`、`api-log`、`security`、`redis`、`mybatis`、`datasource`、`flyway`、`openapi` 与 `storage`。其中 `web`、`api-log`、`security`、`redis`、`mybatis`、`datasource`、`storage` 提供项目默认自动配置；其余 Starter 聚合并沿用对应官方自动配置。Redis Starter 统一持有 Redisson 客户端适配、键命名、原子限流、短时数据与分布式锁实现；`common` 只保留与 Redis 无关的分布式锁接口和异常。项目默认配置使用 Spring Boot `@AutoConfiguration`，在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中显式登记，并以 `@ConditionalOnMissingBean` 允许上层覆盖。配置须支持条件启用并在缺失必要属性时快速失败，禁止依靠扫描整个根包发现基础设施 Bean。新增 Starter 必须对应可被多个业务模块复用的依赖组件，不能把业务模块包装成 Starter。

## 迁移顺序与审核点

1. 完成 BOM、common、framework、modules、launch 五层聚合骨架，并跑通全部既有测试。
2. 审核 T074 后，在 Blog provider/service 中实现 Document/Blog 的 T065–T070。
3. 第二阶段开始前完成 T075：在 Agent provider/service 中隔离 Agent 专属依赖并验证授权边界。
4. 每次模块迁移保持外部 HTTP 合同不变；模块功能完成后按现有流程暂存、审核，再提交合并。

Provider 可以先只包含边界说明，但不得伪造已完成的业务 API；业务代码随对应任务逐项进入 Service。

## 2026-10-04：共享标签并入 System

按维护者确认，仅将 Taxonomy 的共享标签能力并入 System，Portfolio 继续保持独立业务模块。

- System Provider 的 `system.tag.api` 保存 `TaxonomyQueryService` 与 `TechTagData` 跨模块契约。
- System Service 的 `system.tag` 按功能组织标签初始化、Service 接口/实现、Mapper、DO、枚举与常量，不与账号认证文件混放。
- 移除原 Taxonomy 的聚合、Provider、Service 三个 Maven 模块及其依赖声明；Portfolio 仅调整接口导入和废弃依赖，不迁移业务代码。
- 保留既有类名、`tag` 表、自增主键、初始化事务及 PostgreSQL advisory lock、查询筛选/排序和 HTTP 返回合同，不引入新的业务能力或迁移脚本。
