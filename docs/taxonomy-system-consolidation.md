# Taxonomy 并入 System

- 日期：2026-10-04
- 功能分支：`feature/merge-taxonomy-into-system`
- 父分支：`feature/auth-fixed-rsa`，基线 `2e10049`
- 范围：仅迁移共享标签能力；Portfolio 保持独立，不变更认证或其他业务功能。

## 实际改动

1. 原 Taxonomy Provider 的两个契约类迁入 System Provider 的现有 `dev.amai.portfolio.system.api`：`TaxonomyQueryService`、`TechTagData`。
2. 原 Taxonomy Service 的十个类直接迁入 System Service 现有的 `system.config`、`system.constant`、`system.entity.domain`、`system.enums`、`system.mapper`、`system.service`、`system.service.impl`，不再保留单独的 `tag` 包层级。
3. 删除 Taxonomy 聚合、Provider、Service 三份 POM，移除聚合、BOM、Launch、Portfolio 中对应依赖。Maven 项目数由 34 减至 31。
4. Portfolio 只改 `ProfileServiceImpl` 的契约导入与一项废弃 POM 依赖；业务实现、Controller 和公开返回模型不变。
5. 删除旧模块下的生成产物与空目录；源码迁移有 Git 历史可追溯。

## 保持不变

- 类名与 Service 接口/实现关系，避免无关重命名。
- PostgreSQL `tag` 表、自增 ID 和现有迁移脚本；无需数据迁移或重建表。
- 查询仍仅返回推荐技术标签，按分组、排序值、自增 ID 排序。
- 初始化仍为事务内 advisory lock 串行执行，已有技术标签不覆盖，只补缺失内置标签。
- 初始化配置开关、对外 HTTP 合同及前端代码不变。
- 用户已有配置、POM 注释和认证代码修改未纳入本次提交。

## 验证结果

- 迁移前新增归属测试失败，迁移后通过。
- 后端 `clean verify` 成功：77 项测试，76 通过，0 失败，0 错误，1 跳过（真实 OSS 集成测试未启用，本次不做云端写入）。
- 新增两项 PostgreSQL 标签回归：推荐标签筛选及稳定排序、重复初始化不增行且不覆盖已定制标签；测试写入随事务回滚，但 PostgreSQL 自增序列正常消耗不回滚。
- 既有公开资料、管理资料、认证、CSRF、OpenAPI、数据库栈及真实 Redis 回归通过。
- 后端源码与 POM 无旧 Taxonomy 包名或模块引用；前端未改动，未重复执行前端构建。

## 审核注意与验证边界

- 标签文件按职责放入 System 现有包；Portfolio 通过 System Provider 契约使用标签，不依赖 System Service 内部类型。
- 未新增标签管理接口、缓存或其他扩展抽象。
- 本次未新增并发启动压测；初始化事务锁沿用原实现，不宣称新增测试验证了多实例并发初始化。
- 未重启用户当前运行的服务。IDE 需重新加载 Maven 项目后重新构建/运行，以移除旧模块的 classpath。
- 按项目流程提交后等待审核，未合并父分支，未推送远端。

## 审核修正：去除内部子业务层级

- 按维护者要求去掉 `tag` 包层级，12 个标签类归入 System 现有职责目录；跨模块引用与架构测试同步调整，类名和业务行为不变。
- 在 `AGENTS.md` 和编码规范中记录：每个模块内只维护一套三层架构与配套职责包；需要独立业务边界时拆同级 Maven 模块，禁止内部子业务包再嵌套一套三层结构。
- 此修正在原功能分支继续提交，父分支不变；Portfolio 仅更新必要的契约导入，不作其他改动。
