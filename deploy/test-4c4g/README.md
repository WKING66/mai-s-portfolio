# 4c4g 单机测试环境交付包

面向 4 核 4 GB 单机测试环境。**应用容器化、中间件独立**：

| 层 | 组件 | 部署方式 |
| --- | --- | --- |
| 应用 | Nuxt 3000 / Spring 9333 / Python AI 9344 | Docker（`docker-compose.yml`，宿主网络，只绑 `127.0.0.1`） |
| 中间件 | PostgreSQL 16（+ pgvector 0.6.0）、Redis 7.0.15、Nginx 1.24 | 宿主机原生（systemd），与容器生命周期解耦 |

CD 链路：GitHub Actions 构建产物 → 测试机出站拉取 → 部署账号串行部署。

## 文件

| 文件 | 作用 |
| --- | --- |
| `bootstrap-server.sh` | 一次性引导：目录、`mai-ops`/`mai-test`/`mai-deploy` 账号、SSH 授权、双测试库、pgvector、Redis、运行期密钥、服务环境文件 |
| `docker/*.Dockerfile` | 三个应用镜像；只 COPY 构建产物，不在服务器编译 |
| `docker/docker-compose.yml` | 应用编排；中间件不在编排内 |
| `mai-portfolio-deploy.sh` | 根权限受控部署：校验 → 构建镜像 → 双库备份 → 停栈 → Alembic → 切栈 → 就绪 → 记录 → 失败回退 |
| `mai-portfolio-pull.sh` | 部署账号身份运行的拉取器：GitHub Releases API 取权威 digest，镜像加速下载，SHA256 校验 |
| `nginx/mai-portfolio.conf` | 同机 HTTPS 入口，只监听 `127.0.0.1:443`；`/api` 直连 Spring，其余转发 Nuxt |
| `acceptance.py` | 验收脚本（登录、Secure Cookie、CSRF、跨账号隔离、SSE 首段） |
| `perf.py` | 性能测量（延迟分位、SSE 首段、CPU/内存/swap 采样） |
| `build-release-local.sh` | **仅无 CD 权限时**的一次性引导构建，标注 `cdManaged=false` |

## 服务器布局

```
/srv/mai-portfolio/
├── releases/<sha>/          # spring.jar | web.tar.gz | ai.tar.gz | manifest.json | SHA256SUMS
├── current -> releases/<sha>
├── shared/
│   ├── env/{spring,web,ai}.env            # root:mai-deploy 0640
│   ├── secrets/{credentials.env,auth/}    # 0700；私钥 0640 root:mai-deploy
│   ├── public/auth-public.spki.b64        # 公钥（非机密，前端/测试可读）
│   ├── logs/{spring,web,ai}/              # 0770 root:mai-deploy
│   ├── backups/<ts>-<release>/            # 同一停写点的双库 pg_dump
│   └── state/current.json                 # 当前/上一版
└── tmp/
/etc/mai-portfolio/          # docker-compose.yml + 三个 Dockerfile
```

## 账号边界

| 账号 | 能力 | 不允许 |
| --- | --- | --- |
| `mai-ops` | 完整 sudo（需密码） | — |
| `mai-test` | 跑测试；读 `/var/log`、journal、应用日志 | 提权、改产物 |
| `mai-deploy` | 拉取产物；仅 `sudo mai-portfolio-deploy` 与 `mai-portfolio-status` | 任意命令、读密钥 |

## 发布与回退

```bash
# 维护者在 GitHub 手动触发 "test-4c4g release"（默认 dev HEAD，必须已有通过的 pr-gate）
# 服务器侧（部署账号）：
sudo -iu mai-deploy
mai-portfolio-pull                    # 拉最新 test4c4g-* 并部署
mai-portfolio-pull test4c4g-<sha>     # 指定版本
sudo /usr/local/sbin/mai-portfolio-deploy status
sudo /usr/local/sbin/mai-portfolio-deploy rollback          # 仅回退程序
sudo /usr/local/sbin/mai-portfolio-deploy restore-db <dir>  # 结构变更时恢复双库
```

国内直连 GitHub Release 会被限速到 10 KB/s 量级，拉取器默认走
`https://ghfast.top` / `https://gh-proxy.com` / `https://ghproxy.net` 多镜像 + aria2 16 线程，
完整性以 Releases API 返回的 `asset.digest`（sha256）为准，镜像只做传输。

## 入口与隧道

```bash
ssh -N -L 8443:127.0.0.1:443 mai-ops@<server>
# 浏览器打开 https://localhost:8443（自签证书，首次需手动信任）
```
