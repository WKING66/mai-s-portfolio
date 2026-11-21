# 4c4g 单机测试环境 · 交接文档

> 生成时间：2026-11-21
> 主用机：`160.202.241.174`（Ubuntu 24.04.2 LTS，4 核 / 3.8 GB / 30 GB，2 GB swap）
> 备用机：`160.202.241.175`（本次未部署业务，仅用于验证安全组是否真的收敛）

## 0. 对话中途确认的要求与落实情况

| 你的要求 | 落实方式 | 状态 |
| --- | --- | --- |
| 只允许本机 IP 连接两台主机；本地会挂代理 | 安全组与 UFW 都只放行 `22 ← 14.26.224.157/32`（这是服务器**实际看到**的 SSH 来源）。你的代理 HTTPS 出口 `47.129.211.92` 未放行，`198.18.x.x` 一律不用 | 已完成 |
| 云安全组你自己操作，我先给规则 | 已给出唯一规则表；你执行后我从备用机 `160.202.241.175` 反向扫描主用机的 `22/80/443/3000/5432/6379/9333/9344`，全部被拒（含 22），确认是白名单而非仅关业务口 | 已完成并验证 |
| GitHub 国内访问差，要配代理或镜像 | 拉取器默认多镜像（`ghfast.top` / `gh-proxy.com` / `ghproxy.net`）+ aria2 16 线程 + 断点续传；完整性以 GitHub Releases API 的 `asset.digest` 为准，镜像只承担传输。实测直连约 8 KB/s，镜像可达 MB/s 级 | 已完成（未配 HTTP 代理：服务器无可用上游） |
| 补上 Docker，前后端服务用 Docker，数据库/Redis 等中间件单独部署 | 三个应用容器化（`network_mode: host`，只绑 `127.0.0.1`）；PostgreSQL、Redis、Nginx 保持宿主机原生 systemd，与容器生命周期解耦；容器只承载 CI 产物，服务器不编译 | 已完成并复验 |
| 不要再做额外验证测试，尽快交付 | 收到该要求后停止扩展验收矩阵，仅保留：容器化后关键路径复验 6 项、空闲/负载资源采样、页面与 API 延迟 | 已遵守 |
| 生成交接文档 | 本文件；并附 `README.md`（包结构）与两个可直接运行的脚本 | 已完成 |
| 凭据走既有安全渠道 | 密码只落在服务器 `root` 可读文件，不出现在对话、仓库或日志；本机私钥放在 `~/.ssh/mai-portfolio-test4c4g/` | 已完成（sudo 密码领取见 §4） |
| 需要授权时用链接形式 | GitHub PAT 创建链接、PR 打开链接均以 URL 形式给出 | 已完成 |

---

## 1. 做了什么

### 1.1 基础环境

| 组件 | 版本 | 安装方式 |
| --- | --- | --- |
| JDK | 21.0.13.1 | apt（`openjdk-21-jdk-headless`） |
| Node.js | 24.9.0 | 官方 tarball（SHA256 校验）→ `/opt/node` |
| pnpm | 12.4.1 | npm 全局安装（与 `packageManager` 字段一致） |
| Python | 3.12.3 | 系统自带 + `python3-venv` |
| uv | 0.13.9 | 独立 venv 安装到 `/usr/local/bin/uv` |
| PostgreSQL | 16.15 | apt，`listen_addresses=127.0.0.1` |
| pgvector | 0.6.0 | apt（`postgresql-16-pgvector`），装在 AI 库 |
| Redis | 7.0.15 | apt，绑定回环 + 口令 + `maxmemory 256mb` |
| Nginx | 1.24.0 | apt，只监听 `127.0.0.1:80/443` |
| Docker / Compose | 29.1.3 / 2.40.3 | apt（`docker.io`、`docker-compose-v2`），配置了国内镜像源 |

### 1.2 部署形态（应用容器化、中间件独立）

| 层 | 组件 | 形态 |
| --- | --- | --- |
| 应用 | Nuxt 3000、Spring 9333、Python AI 9344 | Docker 容器（`/etc/mai-portfolio/docker-compose.yml`，`network_mode: host`，只绑 `127.0.0.1`） |
| 中间件 | PostgreSQL、Redis、Nginx | 宿主机原生 systemd，与容器生命周期解耦 |

容器只承载 CI 产物，服务器上**不做编译**。

### 1.3 账号与密钥边界

| 账号 | 能力 | 登录方式 |
| --- | --- | --- |
| `mai-ops` | 完整 sudo（需密码） | 公钥 `mai_ops` |
| `mai-test` | 跑测试；读 `/var/log`、journal、应用日志（ACL 只读） | 公钥 `mai_test` |
| `mai-deploy` | 只能 `sudo mai-portfolio-deploy` / `mai-portfolio-status`，外加拉取产物 | 公钥 `mai_deploy` |
| `root` | 禁止远程登录 | 仅云控制台 |

- SSH：`PasswordAuthentication no`、`PermitRootLogin no`、仅公钥（`sshd -T` 已核对）。
- 运行期密钥：RSA-2048（私钥 0640 root:mai-deploy，公钥另放世界可读副本）、
  `AI_SERVER_TOKEN`（Spring 与 Python 同值）、`CONFIG_ENCRYPTION_KEY`（Fernet）、
  Redis 口令、两个数据库口令、自签 TLS 证书（SAN: `localhost`、`127.0.0.1`）。

### 1.4 数据库与 Redis

- Spring 库：`portfolio_test`（owner `portfolio_test`），Flyway V1–V19 已执行。
- AI 库：`mai_portfolio_ai_test`（owner `mai_ai_test`），schema `site_guide_test`，
  已启用 `vector` 扩展 0.6.0，Alembic 迁移到 head。
- Redis：库 1，键前缀 `mai-portfolio:test4c4g`，`requirepass` 已设置，仅回环监听。

### 1.5 网络与入口

- 云安全组：**只保留 1 条** `TCP 22 ← 14.26.224.157/32`，其余入站全部拒绝；无 IPv6。
- 主机防火墙 UFW：`default deny incoming`，同样只放行 `22/tcp ← 14.26.224.157`。
- 业务端口（3000/9333/9344/5432/6379/HTTPS）全部只绑 `127.0.0.1`，只能经 SSH 隧道访问。
- Nginx 同源入口：`/api/*` 直连 Spring，其余转发 Nuxt；`/api/agent/v1/*` 关闭缓冲以支持 SSE。

### 1.6 CD 流水线

- `.github/workflows/test-4c4g-deploy.yml`：`workflow_dispatch` 手动触发。
  - 守卫：提交必须位于 `dev` 历史内，且该提交（或其父提交 / 关联 PR 头提交）有成功的 `pr-gate`。
  - 构建：托管 Runner 上产出 `spring.jar`、`web.tar.gz`、`ai.tar.gz`（含离线 wheel）、`manifest.json`、`SHA256SUMS`，以 Release `test4c4g-<sha12>` 发布。
- 服务器侧 `mai-portfolio-pull`：以 `mai-deploy` 身份出站拉取。
  - 校验值取自 **GitHub Releases API 的 `asset.digest`**，镜像只做传输加速；
    直连约 8 KB/s，默认走 `ghfast.top` / `gh-proxy.com` / `ghproxy.net` + aria2 16 线程。
  - 服务器上不保存任何 GitHub 写权限令牌；仓库公开时完全匿名。
- `mai-portfolio-deploy`（root，经 sudo 只暴露该脚本）：
  校验 → 构建镜像 → **同一停写点双库 pg_dump** → 停栈 → Alembic → 切栈 → 就绪检查 → 写版本记录 → 失败自动回退程序。

---

## 2. 交付了什么

### 2.1 当前运行版本

| 项 | 值 |
| --- | --- |
| Git SHA | `74f8a3a4c915c5665ccf848e269f766d92cf9053`（`dev`） |
| Release | `test4c4g-74f8a3a4c915`（GitHub Release，`builtBy=github-actions`、`cdManaged=true`） |
| 产物 | `spring.jar` 90,624,777 B / `web.tar.gz` 18,971,666 B / `ai.tar.gz` 35,061,646 B |
| 校验 | 服务器侧按 Releases API digest 逐个复核通过 |
| 镜像 | `mai-portfolio-{spring,web,ai}:74f8a3a4c915` |

### 2.2 访问方式

```bash
# 1) 建立隧道（用维护者密钥）
ssh -i <mai_ops 私钥> -N -L 8443:127.0.0.1:443 mai-ops@160.202.241.174

# 2) 浏览器打开
https://localhost:8443          # 自签证书，首次需手动信任
```

### 2.3 主机指纹

```
ED25519  SHA256:4qZu+PolrK1xzBIDDDg3k7c0/Wfr7+Tki9FRBGIdVU8
RSA-3072 SHA256:KMMof6XiC60XNGBHQO1+TFLpxVL/olrz4cuwCLKZGBs
```

维护者密钥指纹：`mai_ops` = `SHA256:VgI0P6uIm11pYdhsqhPk45aTiOyHJpfr1FaIols0Ey8`，
`mai_test` = `SHA256:rikgl7nJroayLNWDf9Ly4fvQ99UJFU2GfNxtkgdqhe8`，
`mai_deploy` = `SHA256:xMmHoRIkxNBhC192M4j+tq2zluZjA1CamVZNEo6obws`。

### 2.4 账号

| 用途 | 用户名 | 密码位置 |
| --- | --- | --- |
| 网站 OWNER | `owner` | `/srv/mai-portfolio/shared/secrets/credentials.env` → `OWNER_PASSWORD` |
| 网站普通账号 ×2 | `tester01` / `tester02` | 同上 → `TEST_USER_1_PASSWORD` / `TEST_USER_2_PASSWORD` |
| 运维 sudo | `mai-ops` | 同上 → `MAI_OPS_PASSWORD`（见第 4 节，需先在控制台设一次） |

领取方式（要求 root 或 mai-ops sudo）：

```bash
sudo grep -E '^(OWNER_PASSWORD|TEST_USER_[12](_PASSWORD)?|MAI_OPS_PASSWORD)=' \
  /srv/mai-portfolio/shared/secrets/credentials.env
```

### 2.5 日志位置

| 位置 | 内容 |
| --- | --- |
| `docker logs mai-portfolio-spring` / `-web` / `-ai` | 容器标准输出（json-file，20 MB×3 滚动） |
| `/srv/mai-portfolio/shared/logs/spring/mai-portfolio.log` | Spring 业务日志 + 接口审计（含脱敏） |
| `/srv/mai-portfolio/shared/logs/ai/alembic.log` | 迁移输出 |
| `/srv/mai-portfolio/shared/backups/<ts>-<release>/` | 每次部署前的双库备份 + `meta.json` |
| `/srv/mai-portfolio/shared/state/current.json` | 当前 / 上一版记录 |

### 2.6 常用运维命令

```bash
sudo -iu mai-deploy
mai-portfolio-pull                       # 拉最新发布并部署
mai-portfolio-pull test4c4g-<sha12>      # 指定版本
sudo /usr/local/sbin/mai-portfolio-deploy status
sudo /usr/local/sbin/mai-portfolio-deploy rollback                 # 仅回退程序
sudo /usr/local/sbin/mai-portfolio-deploy restore-db <备份目录>     # 结构变更时连同数据库回退
```

---

## 3. 已实测 / 未实测

### 3.1 已实测（有输出证据）

- CD 全链路跑通一次：工作流绿 → Release 产出 5 个资产 → 服务器拉取 → digest 校验 → 构建镜像 → 双库备份 → Alembic → 起栈 → 就绪探活全 200。
- 三个容器 `Up`，`spring/web/ai` 探活均 200（经 Nginx 的 `https://127.0.0.1/api/v1/profile` 也是 200）。
- 安全组收敛后，从**另一台公网主机**（175）扫描 174 的 `22/80/443/3000/5432/6379/9333/9344` 全部被拒（含 22）。
- SSH 加固后：`mai-ops` 公钥登录成功、`sudo` 要求密码；`root` 与 `mai-test` 的密码登录均被拒。
- 应用层验收（systemd 阶段 21 项通过；容器化后关键路径 6/6 复验通过）：首页 200、公开资料 200、OWNER 登录、Cookie `Secure`+`HttpOnly`+`SameSite=Lax`、CSRF 缺失时写操作 403、两个普通账号注册与登录、跨账号会话不串号、普通账号访问管理接口 403。
- 登录限流生效（连续调用触发 429，5 次/300 秒）。

### 3.2 容器化后的资源与延迟实测

测试条件：`74f8a3a4c915` 三容器运行、中间件在宿主；客户端经 Nginx 同源 HTTPS 回环访问。

| 场景 | 结果 |
| --- | --- |
| 空闲 30 s | CPU 均值 0.5%（峰值 3.3%），内存 1424 MB / 3884 MB，swap 峰值 1 MB |
| 页面 `/` 并发 4 | 49.0 rps，p50 77.7 ms，p95 112.0 ms，p99 158.7 ms，5xx 0 |
| 页面 `/` 并发 8 | 43.8 rps，p50 178.0 ms，p95 216.7 ms，p99 239.0 ms，5xx 0 |
| API `/api/v1/profile` 并发 4 | 382.9 rps，p50 9.5 ms，p95 16.1 ms，p99 19.8 ms，5xx 0 |
| 压测期间资源 | CPU 均值 73.4%（峰值 89.7%），内存 1437 MB / 3884 MB，无 OOM |

容器化后认证路径复验 6/6 通过：OWNER 登录、Cookie `Secure`+`HttpOnly`+`SameSite=Lax`、
会话查询（roles=`["OWNER"]`）、缺 CSRF 写操作 403、会话创建、turns 打到 Java→Python 链路
（返回 500 属预期：站点 AI 已开启但尚未配置模型供应商）。

首段正文耗时、并发聊天 P95、OOM 计数需真实模型调用，仍待验收。

### 3.3 未实测（缺凭据，标"待验收"）

| 项 | 原因 |
| --- | --- |
| 真实 Chat 与 Embedding | 缺模型供应商测试密钥；站点 AI 开关已打开，但无模型配置，`turns` 返回 500 |
| SSE 端到端首段正文 | 同上；当前只验证到 Nginx→Spring 通道与 `text/event-stream` 路由存在 |
| OSS 资产上传与签名直连 | 缺 OSS AccessKey；当前 `PORTFOLIO_MEDIA_STORAGE=local` |
| 并发聊天下的 CPU/内存/P95 | 依赖真实模型调用 |

---

## 4. 需要立刻处理的一件事

**`mai-ops` 的 sudo 密码只写在 root-only 的 `credentials.env` 里，当前无人可读，因此没人能 sudo。**

这次加固时我把密码设成了随机值并要求 sudo 输入密码，但密码只落在 root 可读文件里，
形成"有钥匙但拿不到钥匙"的循环。请在云控制台 VNC / 串口（root 会话）执行**任选一条**：

```bash
# 方案 A（推荐）：自己定一个 sudo 密码，密码不经过任何聊天或文件
passwd mai-ops

# 方案 B：让维护者账号能读到凭据文件（之后用 sudo 自行领取）
chgrp mai-ops /srv/mai-portfolio/shared/secrets/credentials.env
chmod 0640 /srv/mai-portfolio/shared/secrets/credentials.env
```

顺带这一步也正好验证了「云控制台恢复通道可用」这个交付前提。

---

## 5. 注意事项与已知问题

### 5.1 安全组与来源 IP

- 白名单是 `14.26.224.157/32`，这是服务器**实际看到**的 SSH 来源（家宽，可能变动）。
  地址一变就会失联，恢复只能靠云控制台。
- 你本地挂代理时 HTTPS 出口是 `47.129.211.92`——**不要**把它加进入站规则；SSH 走直连。
- 本机 `198.18.x.x` 是 TUN 保留地址，任何情况下都不能作为安全组来源。

### 5.2 登录限流在隧道下是全局的

Nginx 用 `$remote_addr` 作为 `X-Forwarded-For`，而隧道让所有请求的来源都是 `127.0.0.1`。
于是登录/注册限流（5 次/300 秒）实际是**全站共享**一个桶，多人同时测试会互相挤掉。
如果后续要给多人用，需要改成按真实用户区分（例如隧道内透传客户端标识，或调低该场景的限流口径）。

### 5.3 仓库侧问题（建议单独修）

1. `dev` **未开启分支保护**：CD 的 pr-gate 守卫靠工作流自身校验，缺少平台强制。
2. `dev` 的测试源码编译不过（`AssetQueryServiceImplTest` 等引用了已删除的 `MediaAssetDO` 方法），
   所以发布构建用 `-Dmaven.test.skip=true` 跳过测试编译；这不影响运行，但等于是把质量门禁完全交给 pr-gate。
3. `prod` 档缺少 `portfolio.api-log.*` 与 `portfolio.media.*` 的默认值：
   - 缺 `api-log` 会导致启动时绑定校验失败 → 部署侧用 `SPRING_APPLICATION_JSON` 兜底；
   - 缺 `media` 会导致 `MEDIA_STORAGE` / `MEDIA_LOCAL_DIRECTORY` 环境变量被忽略（默认落到工作目录 `.local-media`）→ 部署侧改用 `PORTFOLIO_MEDIA_*` 完整属性名。
   建议在 `application-prod.yaml` 里补齐默认值。
4. 工作流必须落到**默认分支**后 `workflow_dispatch` 才会出现在 Actions 列表里。

### 5.4 部署脚本的安全边界

`mai-deploy` 只能 `sudo` 调受控脚本，不能执行任意命令；但脚本内部以 root 展开
`mai-deploy` 可写的改包（`tar -xzf`、`chown -R`）。在产物 digest 校验到位的前提下风险可控，
但如果 `mai-deploy` 账号本身被攻破，这条路径构成提权面。建议后续把展开动作降权到 `mai-deploy` 执行。

### 5.5 回退语义

- 普通部署失败：自动回退程序（镜像回滚），不动数据库。
- 涉及结构变更：失败后必须用 `restore-db` 同时恢复两库，再回退程序；
  只回退程序而不回退库会出现新旧 schema 不匹配。

---

## 6. 缺项清单（一次性列清）

| 缺什么 | 影响 | 能怎么补 |
| --- | --- | --- |
| 云安全组 API / 控制台账号 | 我无法自行收紧或恢复入站规则；已由你手动完成 | 你已完成，规则见 §1.5 |
| `mai-ops` sudo 密码 | 当前无人可 sudo（见 §4） | 控制台执行 `passwd mai-ops` |
| 模型供应商测试 Key | 真实 Chat / Embedding / SSE 正文 / 并发聊天 P95 无法验收 | 在后台「站点与限额」配置供应商地址、Key、模型 ID |
| OSS 测试 AccessKey | 资产上传与签名直连无法验收 | 提供后把 `PORTFOLIO_MEDIA_*` 改为 `oss` 并重启 spring 容器 |
| GitHub `Pull requests: write` | 我无法创建 PR（已尝试，403）；分支已推送 | 你用 https://github.com/WKING66/mai-s-portfolio/pull/new/codex/test-4c4g-docker 直接开 |

---

## 7. 未完成的测试项（逐项标注）

以下项目**没有实测**，不能当作已验收；括号内是阻塞原因。

| # | 测试项 | 状态 | 阻塞原因 |
| --- | --- | --- | --- |
| 1 | 真实 Chat 对话（模型返回正文） | 未测 | 缺模型供应商测试 Key |
| 2 | 真实 Embedding | 未测 | 同上 |
| 3 | SSE 首段正文耗时（TTFB 到首个 `delta`） | 未测 | 无模型返回；仅验证到请求进入 Java→Python 链路后返回 500 |
| 4 | 并发聊天时的 CPU / 内存 / swap / P95 | 未测 | 同上 |
| 5 | 聊天负载下的 5xx 与 OOM 计数 | 未测 | 同上（页面/API 负载下 5xx=0、无 OOM 已测） |
| 6 | OSS 上传、签名直连、1 小时过期 | 未测 | 缺 OSS AccessKey；当前 `PORTFOLIO_MEDIA_STORAGE=local` |
| 7 | 资源级跨账号隔离（A 读 B 的会话/执行/资产） | 部分 | 已测「会话不串号」与「普通账号访问管理接口 403」；逐资源越权未穷举 |
| 8 | 回退演练（`rollback`） | 未演练 | 脚本已实现但未实机执行（会把当前版本回退，属破坏性操作） |
| 9 | 双库恢复演练（`restore-db`） | 未演练 | 同上 |
| 10 | 数据库结构变更路径（停机点备份→迁移→失败保留日志） | 未演练 | 本次部署为首次建库，没有可对比的上一版结构 |
| 11 | M2 索引并行场景复测 | 不适用 | M2 尚未实现；pgvector 0.6.0 与 `site_guide_test` schema 已就绪 |
| 12 | 云控制台恢复通道验证 | 待你确认 | 需你在控制台执行 `passwd mai-ops`（见 §4） |
| 13 | 备用机 `160.202.241.175` 业务部署与加固 | 未做 | 按任务定位只作备用；本次仅用于安全组反向验证 |
| 14 | `mai-ops` sudo 可用性 | 阻塞 | 密码只存在于 root 可读文件（见 §4） |

> 结论：环境、CD、账号体系、安全边界、页面/API 性能可用；**凡涉及模型或 OSS 的功能链路一律未验收**。
