#!/usr/bin/env bash
# 4c4g 单机测试环境的一次性引导脚本。
#
# 职责：创建目录骨架、独立账号、SSH 授权、数据库与 Redis、运行期密钥。
# 约束：可重复执行（幂等）；不覆盖已存在的密钥与账号密码；只在本机执行。
# 使用：以 root 运行 ./bootstrap-server.sh
set -euo pipefail

BASE=/srv/mai-portfolio
SECRETS="$BASE/shared/secrets"
ENVDIR="$BASE/shared/env"
LOGS="$BASE/shared/logs"

OPS_PUBKEY='ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGPAwq4VhyIbc6dFFsJ1uuN6IUFDKQwYMdmZKwYTt8+s mai-portfolio-test4c4g-ops'
TEST_PUBKEY='ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAgRJW3z5pN6JOJJ62PX55IUi/a2hLQHUpo4t4sy2h2c mai-portfolio-test4c4g-test'
DEPLOY_PUBKEY='ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJ4HHRwb28tP1zby8W1s61/LRRMXusy82VrKicHHZNQ3 mai-portfolio-test4c4g-deploy'

log() { printf '[bootstrap] %s\n' "$*"; }

# ---------------------------------------------------------------- 目录
log "创建目录骨架"
install -d -m 0755 "$BASE"
install -d -m 0755 "$BASE/releases"
install -d -m 0755 "$BASE/tmp"
install -d -m 0751 "$BASE/shared"
install -d -m 0700 "$SECRETS"
install -d -m 0750 "$ENVDIR"
install -d -m 0750 "$BASE/shared/state"
install -d -m 0750 "$BASE/shared/backups"
for svc in spring web ai; do install -d -m 0770 "$LOGS/$svc"; done

# ---------------------------------------------------------------- 账号
log "创建独立账号 mai-ops / mai-test / mai-deploy"
for user in mai-ops mai-test mai-deploy; do
  if ! id "$user" >/dev/null 2>&1; then
    useradd --create-home --shell /bin/bash "$user"
  fi
done

# 测试账号：可读日志与 journal，不能提权。
usermod -aG adm,systemd-journal mai-test

install -d -m 0700 -o mai-ops -g mai-ops /home/mai-ops/.ssh
install -d -m 0700 -o mai-test -g mai-test /home/mai-test/.ssh
install -d -m 0700 -o mai-deploy -g mai-deploy /home/mai-deploy/.ssh
printf '%s\n' "$OPS_PUBKEY" > /home/mai-ops/.ssh/authorized_keys
printf '%s\n' "$TEST_PUBKEY" > /home/mai-test/.ssh/authorized_keys
printf '%s\n' "$DEPLOY_PUBKEY" > /home/mai-deploy/.ssh/authorized_keys
chown mai-ops:mai-ops /home/mai-ops/.ssh/authorized_keys
chown mai-test:mai-test /home/mai-test/.ssh/authorized_keys
chown mai-deploy:mai-deploy /home/mai-deploy/.ssh/authorized_keys
chmod 0600 /home/{mai-ops,mai-test,mai-deploy}/.ssh/authorized_keys

# 工作目录：测试账号跑测试，部署账号只读产物。
install -d -m 0750 -o mai-test -g mai-test /home/mai-test/work
chown -R mai-deploy:mai-deploy "$BASE/releases" "$BASE/tmp" "$BASE/shared/media"
chgrp -R mai-deploy "$BASE/shared/logs" "$BASE/shared/state" "$BASE/shared/env"
chmod 0770 "$LOGS" "$LOGS"/*
find "$LOGS" -type d -exec chmod g+s {} \;
# 测试账号只读日志：目录默认 ACL 保留组写权限，否则新日志文件服务写不进去。
setfacl -R -d -m u:mai-test:r-X -d -m g::rwx -d -m m::rwx "$LOGS" 2>/dev/null || true

# 维护者：完整 sudo。部署账号：只允许运行本项目受控脚本。
cat > /etc/sudoers.d/90-mai-ops <<'EOF'
mai-ops ALL=(ALL:ALL) ALL
EOF
cat > /etc/sudoers.d/90-mai-deploy <<'EOF'
# 部署账号只能通过受控脚本管理本项目服务，不能执行任意命令。
mai-deploy ALL=(root) NOPASSWD: /usr/local/sbin/mai-portfolio-deploy
mai-deploy ALL=(root) NOPASSWD: /usr/local/sbin/mai-portfolio-status
EOF
chmod 0440 /etc/sudoers.d/90-mai-ops /etc/sudoers.d/90-mai-deploy
visudo -c >/dev/null

# 测试账号只读应用日志。
if command -v setfacl >/dev/null; then
  setfacl -R -m u:mai-test:r-X "$LOGS"
  setfacl -R -d -m u:mai-test:r-X "$LOGS"
fi

# ---------------------------------------------------------------- 运行期密钥
log "生成运行期密钥（已存在则跳过）"
CRED="$SECRETS/credentials.env"
if [ ! -f "$CRED" ]; then
  umask 077
  {
    echo "# 4c4g 测试环境账号与数据库口令。仅 root 可读；交付时通过安全渠道领取。"
    printf 'OWNER_PASSWORD=%s\n' "$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)Aa1!"
    printf 'TEST_USER_1=tester01\n'
    printf 'TEST_USER_1_PASSWORD=%s\n' "$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)Aa1!"
    printf 'TEST_USER_2=tester02\n'
    printf 'TEST_USER_2_PASSWORD=%s\n' "$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)Aa1!"
    printf 'DB_SPRING_USER=portfolio_test\n'
    printf 'DB_SPRING_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'DB_AI_USER=mai_ai_test\n'
    printf 'DB_AI_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'REDIS_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'AI_SERVER_TOKEN=%s\n' "$(python3 -c 'from secrets import token_urlsafe; print(token_urlsafe(48))')"
    printf 'CONFIG_ENCRYPTION_KEY=%s\n' "$(python3 -c 'import base64,os; print(base64.urlsafe_b64encode(os.urandom(32)).decode())')"
  } > "$CRED"
  chmod 0600 "$CRED"
else
  log "credentials.env 已存在，复用"
fi
# shellcheck disable=SC1090
. "$CRED"

# RSA-2048：后端持 PKCS#8 私钥，前端持 SPKI 公钥（base64 DER）。
AUTH_DIR="$SECRETS/auth"
install -d -m 0700 "$AUTH_DIR"
if [ ! -f "$AUTH_DIR/private.pem" ]; then
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$AUTH_DIR/private.pem" 2>/dev/null
  openssl pkey -in "$AUTH_DIR/private.pem" -pubout -outform DER -out "$AUTH_DIR/public.der"
  base64 -w0 "$AUTH_DIR/public.der" > "$AUTH_DIR/public.spki.b64"
  chmod 0600 "$AUTH_DIR/private.pem"
  chmod 0644 "$AUTH_DIR/public.der" "$AUTH_DIR/public.spki.b64"
fi

# 私钥只给服务组读；公钥不是机密，额外放一份世界可读副本供前端与测试使用。
chgrp mai-deploy "$AUTH_DIR" "$AUTH_DIR/private.pem"
chmod 0750 "$AUTH_DIR"
chmod 0640 "$AUTH_DIR/private.pem"
install -d -m 0755 "$BASE/shared/public"
install -m 0644 "$AUTH_DIR/public.spki.b64" "$BASE/shared/public/auth-public.spki.b64"

# TLS：同机 HTTPS 测试入口使用自签证书，浏览器经 SSH 隧道访问。
TLS_DIR="$SECRETS/tls"
install -d -m 0755 "$TLS_DIR"
if [ ! -f "$TLS_DIR/server.crt" ]; then
  openssl req -x509 -newkey rsa:2048 -sha256 -days 365 -nodes \
    -keyout "$TLS_DIR/server.key" -out "$TLS_DIR/server.crt" \
    -subj "/CN=mai-portfolio-test4c4g" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1" 2>/dev/null
  chmod 0600 "$TLS_DIR/server.key"
fi

# ---------------------------------------------------------------- PostgreSQL
log "配置 PostgreSQL 双测试库与 pgvector"
run_psql() { # run_psql <sql> [db]
  su - postgres -c "psql -v ON_ERROR_STOP=1 -d '${2:-postgres}' -tAc \"$1\""
}

ensure_role() { # ensure_role <role> <password>
  if [ "$(run_psql "SELECT 1 FROM pg_roles WHERE rolname='$1'")" = "1" ]; then
    run_psql "ALTER ROLE $1 PASSWORD '$2'" >/dev/null
  else
    run_psql "CREATE ROLE $1 LOGIN PASSWORD '$2'" >/dev/null
  fi
}

ensure_role "${DB_SPRING_USER}" "${DB_SPRING_PASSWORD}"
ensure_role "${DB_AI_USER}" "${DB_AI_PASSWORD}"

for pair in "portfolio_test:${DB_SPRING_USER}" "mai_portfolio_ai_test:${DB_AI_USER}"; do
  db="${pair%%:*}"; owner="${pair##*:}"
  if [ "$(run_psql "SELECT 1 FROM pg_database WHERE datname='${db}'")" != "1" ]; then
    run_psql "CREATE DATABASE ${db} OWNER ${owner}" >/dev/null
  fi
done

# pgvector 供 M2 使用；扩展安装到 AI 库 public schema。
run_psql "CREATE EXTENSION IF NOT EXISTS vector" mai_portfolio_ai_test >/dev/null
PGVECTOR_VERSION=$(run_psql "SELECT extversion FROM pg_extension WHERE extname='vector'" mai_portfolio_ai_test)
log "pgvector 已启用：${PGVECTOR_VERSION:-未启用}"

# 只监听回环：postgresql.conf 显式收紧。
PG_CONF=/etc/postgresql/16/main/postgresql.conf
for kv in "listen_addresses = '127.0.0.1'" "shared_buffers = 256MB" "effective_cache_size = 768MB" "work_mem = 8MB" "maintenance_work_mem = 64MB" "max_connections = 100" "log_timezone = 'Asia/Shanghai'"; do
  key="${kv%% =*}"
  if grep -qE "^[#[:space:]]*${key}[[:space:]]*=" "$PG_CONF"; then
    sed -i -E "s|^[#[:space:]]*${key}[[:space:]]*=.*|${kv}|" "$PG_CONF"
  else
    printf '%s\n' "$kv" >> "$PG_CONF"
  fi
done
systemctl restart postgresql
systemctl is-active --quiet postgresql && log "PostgreSQL 已重启"

# ---------------------------------------------------------------- Redis
log "配置 Redis（回环 + 口令 + 命名空间）"
REDIS_CONF=/etc/redis/redis.conf
sed -i -E "s|^[#[:space:]]*bind .*|bind 127.0.0.1 -::1|" "$REDIS_CONF"
sed -i -E "s|^[#[:space:]]*protected-mode .*|protected-mode yes|" "$REDIS_CONF"
if grep -qE "^[#[:space:]]*requirepass " "$REDIS_CONF"; then
  sed -i -E "s|^[#[:space:]]*requirepass .*|requirepass ${REDIS_PASSWORD}|" "$REDIS_CONF"
else
  printf 'requirepass %s\n' "$REDIS_PASSWORD" >> "$REDIS_CONF"
fi
# 单机测试实例：限制内存上限，避免与 JVM/Nuxt 争抢。
sed -i -E "s|^[#[:space:]]*maxmemory .*|maxmemory 256mb|" "$REDIS_CONF" || true
grep -q '^maxmemory ' "$REDIS_CONF" || printf 'maxmemory 256mb\n' >> "$REDIS_CONF"
grep -q '^maxmemory-policy ' "$REDIS_CONF" || printf 'maxmemory-policy noeviction\n' >> "$REDIS_CONF"
systemctl enable --now redis-server >/dev/null 2>&1 || true
systemctl restart redis-server
sleep 1
redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping
redis-server --version | awk '{print "redis " $3}'

# ---------------------------------------------------------------- 服务环境文件
log "写入服务环境文件"
PUBKEY_B64=$(cat "$AUTH_DIR/public.spki.b64")

cat > "$ENVDIR/spring.env" <<EOF
# Spring 运行环境（4c4g 测试）。由 systemd EnvironmentFile 注入。
SPRING_PROFILES_ACTIVE=prod
SPRING_DATASOURCE_DRUID_URL=jdbc:postgresql://127.0.0.1:5432/portfolio_test
SPRING_DATASOURCE_DRUID_USERNAME=${DB_SPRING_USER}
SPRING_DATASOURCE_DRUID_PASSWORD=${DB_SPRING_PASSWORD}
SPRING_DATA_REDIS_HOST=127.0.0.1
SPRING_DATA_REDIS_PORT=6379
SPRING_DATA_REDIS_PASSWORD=${REDIS_PASSWORD}
SPRING_DATA_REDIS_DATABASE=1
PORTFOLIO_REDIS_KEY_PREFIX=mai-portfolio:test4c4g
PORTFOLIO_SECURITY_RSA_PRIVATE_KEY=file:${AUTH_DIR}/private.pem
PORTFOLIO_SECURITY_ALLOWED_ORIGINS=https://localhost:8443
SITE_GUIDE_AI_BASE_URL=http://127.0.0.1:9344
SITE_GUIDE_AI_TOKEN=${AI_SERVER_TOKEN}
PORTFOLIO_BOOTSTRAP_ENABLED=true
PORTFOLIO_BOOTSTRAP_INITIAL_PASSWORD=${OWNER_PASSWORD}
SPRINGDOC_API_DOCS_ENABLED=false
LOGGING_SWAGGER_UI_ENABLED=false
LOG_FILE=${LOGS}/spring/mai-portfolio.log
LOG_FILE_PATTERN=${LOGS}/spring/mai-portfolio.%d{yyyy-MM-dd}.%i.log.gz
# prod 档未提供 portfolio.api-log 默认值，缺失会因绑定校验失败而无法启动。
SPRING_APPLICATION_JSON={"portfolio":{"api-log":{"enabled":true,"max-payload-length":2048,"additional-sensitive-fields":[]}}}
MEDIA_STORAGE=local
PORTFOLIO_MEDIA_STORAGE=local
PORTFOLIO_MEDIA_LOCAL_DIRECTORY=${BASE}/shared/media
MEDIA_SIGNED_URL_TTL=1h
# prod 档未提供 portfolio.media 绑定，环境变量必须用完整属性名。
# OSS 测试凭据由维护者提供后改为 oss 并补齐下列四项：
# PORTFOLIO_MEDIA_STORAGE=oss
# PORTFOLIO_MEDIA_OSS_ENDPOINT=
# PORTFOLIO_MEDIA_OSS_BUCKET_NAME=
# PORTFOLIO_MEDIA_OSS_ACCESS_KEY_ID=
# PORTFOLIO_MEDIA_OSS_ACCESS_KEY_SECRET=
EOF

cat > "$ENVDIR/ai.env" <<EOF
# Python AI 服务运行环境。
DATABASE_URL=postgresql://${DB_AI_USER}:${DB_AI_PASSWORD}@127.0.0.1:5432/mai_portfolio_ai_test
AI_DB_SCHEMA=site_guide_test
AI_SERVER_TOKEN=${AI_SERVER_TOKEN}
CONFIG_ENCRYPTION_KEY=${CONFIG_ENCRYPTION_KEY}
EOF

cat > "$ENVDIR/web.env" <<EOF
# Nuxt 运行环境。
NODE_ENV=production
HOST=127.0.0.1
PORT=3000
NUXT_BACKEND_URL=http://127.0.0.1:9333
NUXT_PUBLIC_AUTH_RSA_PUBLIC_KEY=${PUBKEY_B64}
NUXT_PUBLIC_SITE_URL=https://localhost:8443
EOF

chmod 0640 "$ENVDIR"/*.env
chown root:mai-deploy "$ENVDIR"/*.env
chmod 0700 "$ENVDIR"
install -d -m 0750 -o mai-deploy -g mai-deploy "$BASE/shared/media"

log "完成"
