# PostgreSQL 迁移目录

`application.yml` 已将 `classpath:db/postgresql` 配置为当前 Flyway 扫描目录。

`V1__portfolio_and_document_core.sql` 已在本地开发库执行，必须视为不可变历史；其中早期留下的“尚未切换扫描目录”头部说明不再代表当前运行状态，但不能只为修改注释而改变迁移校验和。后续结构或数据库注释调整一律新增 `V2__...sql` 及更高版本迁移，不回写 V1。
