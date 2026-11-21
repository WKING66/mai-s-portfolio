# 4c4g 单机测试环境交付包

面向 4 核 4 GB 单机测试环境（Nuxt 3000 + Spring 9333 + Python 9344 + Nginx + PostgreSQL 16 + Redis），
以及「GitHub Actions 构建 → 测试机出站拉取 → 部署账号串行部署」的 CD 链路。

## 文件

| 文件 | 作用 |
| --- | --- |
| `bootstrap-server.sh` | 一次性引导：目录、`mai-ops`/`mai-test`/`mai-deploy` 账号、SSH 授权、双测试库、pgvector、Redis、运行期密钥、服务环境文件 |
| `build-release-local.sh` | **仅作无 CD 权限时的一次性引导构建**，产物布局与 CD 一致，标注 `cdManaged=false` |
| `systemd/*.service` | 三个服务的运行单元（只监听回环，堆/内存上限按 4 GB 调） |
| `nginx/mai-portfolio.conf` | 同机 HTTPS 入口，只监听 `127.0.0.1:443`，`/api` 直连 Spring，其余转发 Nuxt |
| `mai-portfolio-deploy.sh` | 根权限受控部署脚本：校验 → 双库备份 → 串行停机 → Alembic → 切换 → 就绪检查 → 版本记录 → 失败回退 |
| `mai-portfolio-pull.sh` | 部署账号身份运行的拉取器：从 GitHub Release 出站下载、校验、转交部署脚本 |

## 服务器布局

```
/srv/mai-portfolio/
├── releases/<sha>/          # spring.jar | web.tar.gz | ai.tar.gz | manifest.json | SHA256SUMS
├── current -> releases/<sha>
├── shared/
│   ├── env/{spring,web,ai}.env        # root:mai-deploy 0640，systemd 注入
│   ├── secrets/{credentials.env,auth/,tls/}   # 0700，仅 root
│   ├── logs/{spring,web,ai}/          # mai-deploy 可写，mai-test 只读（ACL）
│   ├── backups/<ts>-<release>/{spring.dump,ai.dump,meta.json}
│   └── state/current.json             # 当前/上一版 release 记录
└── tmp/
```

## 账号边界

| 账号 | 能力 | 不允许 |
| --- | --- | --- |
| `mai-ops` | 完整 sudo | — |
| `mai-test` | 跑测试；读 `/var/log`、journal、应用日志 | 提权、改产物 |
| `mai-deploy` | 拉取产物；仅 `sudo mai-portfolio-deploy` 与 `mai-portfolio-status` | 任意命令、读密钥 |

## 发布与回退

```bash
# 维护者在 GitHub 手动触发 test-4c4g release（默认 dev HEAD，必须已有通过的 pr-gate）
# 服务器侧（部署账号）：
sudo -iu mai-deploy
mai-portfolio-pull                  # 拉取最新 test4c4g-* 并部署
mai-portfolio-pull test4c4g-<sha>   # 指定版本
sudo /usr/local/sbin/mai-portfolio-deploy status
sudo /usr/local/sbin/mai-portfolio-deploy rollback          # 仅回退程序
sudo /usr/local/sbin/mai-portfolio-deploy restore-db <dir>  # 结构变更时恢复双库
```

- 普通部署失败：脚本自动回退程序，不触碰数据库。
- 数据库结构变更：脚本在任何切换前先做**同一停写点**的双库 `pg_dump`，并校验可列出内容；
  迁移失败保留 `shared/logs/ai/alembic.log`。回退结构必须用 `restore-db` 同时恢复两库。
- 只保留最近 3 个 release。

## 入口与隧道

Nginx 只在回环监听，浏览器经 SSH 隧道访问：

```bash
ssh -N -L 8443:127.0.0.1:443 mai-ops@<server>
# 然后打开 https://localhost:8443 （自签证书，首次需手动信任）
```
