# 4 核 4 GB 单机测试部署

A 运行 Nuxt、Spring 和 AI 三个 Compose 容器，PostgreSQL 16 + pgvector、Redis 和 Nginx 由宿主机维护。各业务端口只监听回环，站点经 SSH 转发访问。B 保持备用。

## 首次安装与现有主机接入

在维护窗口前准备 Docker Engine、Compose 2.30 以上、PostgreSQL 客户端、jq、curl、util-linux 和 Nginx。先建立 `portfolio_test` 与 `mai_portfolio_ai_test`，分别分配应用数据库账号。向 AI 库安装 pgvector，并按 AI 环境变量约定准备测试 schema。Redis 使用单独的测试数据库、口令和键前缀。限制 PostgreSQL、Redis 和 Nginx 的业务监听为回环。云安全组只允许已核对的 SSH 出口访问 22，维护者先验证独立控制台恢复。安装过程沿用已有 SSH 账号、公钥和防火墙。

以维护者权限创建无登录 shell 的宿主账号 `mai-spring`，运行脚本会读取其实际 UID/GID。将共用的媒体和 Spring 日志目录授权给该账号，将认证私钥设为该账号可读，保留只读容器挂载。检查父目录的遍历权限，部署账号无需读取密钥或应用环境文件。Nginx 需要维护者提供适用于 localhost 和 127.0.0.1 的测试 TLS 证书，启用 `nginx/mai-portfolio.conf` 前先运行 `nginx -t`。

将三个 Dockerfile 和 `docker-compose.yml` 安装到 `/etc/mai-portfolio/`，由 root 拥有且仅 root 可修改。部署脚本安装为 `/usr/local/sbin/mai-portfolio-deploy`（root:root 0755），拉取入口安装为 `/usr/local/bin/mai-portfolio-pull`（root:root 0755）。用 `visudo` 移除旧助手及旧状态脚本的 sudo 授权，仅保留下列入口。

```sudoers
mai-deploy ALL=(root) NOPASSWD: /usr/local/sbin/mai-portfolio-deploy ^deploy\ test4c4g-[0-9a-f]{12}$, /usr/local/sbin/mai-portfolio-deploy status
```

脚本在 root 私有的 `/var/lib/mai-portfolio-deploy` 保存活动版本、未完成切换和备份。接入已有 Compose 环境前，由维护者核对三个容器镜像标签与原发布记录，将当前完整标签写入该目录的 `current.json`，例如 `{"release":"test4c4g-0123456789ab","previous":""}`。核对原 Compose 项目是 `mai-portfolio`。使用 systemd 跑应用的旧环境在切换前由维护者停用旧应用 unit 并确认 3000、9333 和 9344 均已释放。

## 运行配置

将现有配置通过安全渠道注入 `/srv/mai-portfolio/shared/env/{spring,ai,web}.env`，文件由 root 管理，只供 Compose 读取。Compose 使用原样值，每行 `NAME=value`，值里的引号会作为值的一部分。

| 组件 | 必须配置或核对的变量 |
| --- | --- |
| Spring | `SPRING_PROFILES_ACTIVE=prod`，`SPRING_DATASOURCE_DRUID_URL`、`SPRING_DATASOURCE_DRUID_USERNAME`、`SPRING_DATASOURCE_DRUID_PASSWORD`，`REDIS_PASSWORD`、`REDIS_DATABASE`、`REDIS_KEY_PREFIX`，`AUTH_RSA_PRIVATE_KEY_LOCATION` 指向已挂载的 PEM URI，ADMIN_ALLOWED_ORIGINS 指定实际 HTTPS 站点来源，`SITE_GUIDE_AI_TOKEN` 与 AI 侧一致。 |
| 日志与本地媒体 | `LOG_FILE=/srv/mai-portfolio/shared/logs/spring/mai-portfolio.log`，`MEDIA_LOCAL_DIRECTORY=/srv/mai-portfolio/shared/media`，`MEDIA_STORAGE=local`。 |
| OSS | 使用独立测试 Bucket，或在共享私有 Bucket 中设置专用 `OSS_KEY_PREFIX`（如 `test/mai-portfolio-a/`），并将凭据的对象权限限于该前缀。设置 `MEDIA_STORAGE=oss`、`OSS_ENDPOINT`、`OSS_BUCKET_NAME`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`。 |
| AI 与 Web | AI 需要 `DATABASE_URL`、`AI_DB_SCHEMA`、`AI_SERVER_TOKEN` 与 `CONFIG_ENCRYPTION_KEY`；通过管理界面配置模型。Web 使用同机 API 地址和回环监听。 |

GitHub 公共 Release 的元数据通过直连 TLS 核对。若大文件需要代理，由维护者在 `/etc/mai-portfolio/github-transfer.curl` 配置受信任的出站代理，文件 root-only，仅用于传输，不增加入站端口。测试主机无需 GitHub 写权限令牌。直连和代理都太慢时，可为 mai-deploy 准备可写的 /srv/mai-portfolio/incoming 目录，然后通过现有 SSH 将官方同名资产上传到该目录的完整标签子目录。部署入口仍独立向 GitHub 取证，只接受匹配摘要的私有副本。维护者在发布成功后清理已上传的临时文件。

新建数据库首次启动时，由维护者通过环境注入 PORTFOLIO_BOOTSTRAP_ENABLED=true 和 PORTFOLIO_BOOTSTRAP_INITIAL_PASSWORD，初始化站点与管理员。初始化成功后移除初始密码并关闭该开关。已有站点继续使用现有数据库与账号。

旧环境从本地存储切换到 OSS 前，按数据库的逻辑对象键把已引用文件复制到目标前缀，逐项校验内容并保留本地副本。确认发布验收通过后再处理本地数据。

## 发布和失败恢复

1. 确认候选是 `dev` 第一父链上的完整 SHA，且该 SHA 的 `PR gate` push 运行成功。部署相关改动进入 dev 后，工作流自动构建并把资产暂存到草稿。工作流进入默认分支后，也可从 `dev` 手动运行 `test-4c4g release candidate`，传入完整 SHA。
2. 部署负责人核对候选 SHA、门禁、构建和全部官方资产摘要，确认同名标签未创建或指向候选 SHA，随后直接发布为测试预发布版。发布后核对标签指向及正式版本的 Latest 标记，再运行 `mai-portfolio-pull test4c4g-<sha12>`。部署助手验证官方标签、发布状态、资产长度和完整 SHA-256，并在特权目录隔离下载。镜像内使用非 root 账号解包和迁移。
3. 仅在获批窗口切换。该脚本先确认应用监听已消失，并确认两个测试库都没有其他客户端，再导出两库。切换期间确保没有外部作业重连写入。备份失败时尝试恢复切换前实际运行的版本。
4. 迁移开始后的失败会停止候选，保留 `pending.json`、该次配对备份和迁移日志。维护者核对两库状态，必要时从同一备份点恢复两库，再用记录中的 `previous` 启动切换前版本。确认健康、数据库和活动版本记录一致后才清理 `pending.json`。数据库恢复由维护者操作。
5. 部署成功后查看 `sudo -n /usr/local/sbin/mai-portfolio-deploy status`，核对活动标签、容器和业务探活。确认健康后保留当前版和一个可回退的上一版镜像。只清理属于该部署且超过保留范围的容器和镜像，避免全局清理。数据库备份按恢复需要保留。

## 验收

通过现有 SSH 白名单建立本机隧道，检查首页、公开 API、登录、跨账号和边界。用真实测试模型核对流式正文与终态，单独核对 Embedding 和 OSS 签名读取。用外部客户端的流式负载配合宿主机资源指标报告结果。
