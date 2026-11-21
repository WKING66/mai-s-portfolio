#!/usr/bin/env bash
# 根权限部署助手：只做「校验产物 → 双库备份 → 串行停机/迁移/切换 → 就绪检查 → 记录 → 失败回退」。
# 安装位置：/usr/local/sbin/mai-portfolio-deploy（root:root 0755）
# 调用方：mai-deploy 经 sudo 调用，参数只能来自本文档定义的子命令。
set -uo pipefail

BASE=/srv/mai-portfolio
REL="$BASE/releases"
CUR="$BASE/current"
STATE="$BASE/shared/state"
ENV_SPRING="$BASE/shared/env/spring.env"
ENV_AI="$BASE/shared/env/ai.env"
LOGDIR="$BASE/shared/logs"
BACKUPS="$BASE/shared/backups"
COMPOSE_SERVICES="mai-portfolio-ai mai-portfolio-spring mai-portfolio-web"

die() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }
say() { printf '[deploy] %s\n' "$*"; }

now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

env_get() { # env_get <file> <key>
  awk -F= -v k="$2" '$1==k { sub(/^[^=]*=/, ""); print; exit }' "$1"
}

svc_wait() { # svc_wait <url> <seconds> <name>
  local url="$1" limit="$2" name="$3" i=0 code
  while [ "$i" -lt "$limit" ]; do
    code=$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 3 "$url" || true)
    case "$code" in 200|204) say "$name 就绪（HTTP $code）"; return 0 ;; esac
    i=$((i + 1)); sleep 1
  done
  return 1
}

stop_all() {
  systemctl stop mai-portfolio-web mai-portfolio-spring mai-portfolio-ai 2>/dev/null || true
  sleep 1
}

start_ai() {
  systemctl start mai-portfolio-ai || return 1
  local token; token=$(env_get "$ENV_AI" AI_SERVER_TOKEN)
  local i=0 code
  while [ "$i" -lt 60 ]; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
      -H "Authorization: Bearer $token" http://127.0.0.1:9344/internal/v1/health/ready || true)
    [ "$code" = 200 ] && { say "Python 就绪"; return 0; }
    i=$((i + 1)); sleep 1
  done
  return 1
}

start_spring() {
  systemctl start mai-portfolio-spring || return 1
  svc_wait "http://127.0.0.1:9333/api/v1/profile" 180 "Spring"
}

start_web() {
  systemctl start mai-portfolio-web || return 1
  svc_wait "http://127.0.0.1:3000/" 90 "Nuxt"
}

backup_dbs() { # backup_dbs <tag>
  local dir="$BACKUPS/$1"
  install -d -m 0750 "$dir"
  local spring_url spring_user spring_pass ai_url
  spring_url=$(env_get "$ENV_SPRING" SPRING_DATASOURCE_DRUID_URL)
  spring_user=$(env_get "$ENV_SPRING" SPRING_DATASOURCE_DRUID_USERNAME)
  spring_pass=$(env_get "$ENV_SPRING" SPRING_DATASOURCE_DRUID_PASSWORD)
  ai_url=$(env_get "$ENV_AI" DATABASE_URL)
  local db_spring db_ai
  db_spring=$(printf '%s' "$spring_url" | sed -E 's|.*/([^/?]+).*|\1|')
  db_ai=$(printf '%s' "$ai_url" | sed -E 's|.*/([^/?]+).*|\1|')
  # 同一停写点：先停全部写方，再顺序导出两库。
  PGPASSWORD="$spring_pass" pg_dump -h 127.0.0.1 -U "$spring_user" -Fc -d "$db_spring" -f "$dir/spring.dump" || return 1
  local ai_pw; ai_pw=$(printf '%s' "$ai_url" | sed -E 's|^[^:]+://[^:]+:([^@]+)@.*|\1|')
  local ai_user; ai_user=$(printf '%s' "$ai_url" | sed -E 's|^[^:]+://([^:]+):.*@.*|\1|')
  PGPASSWORD="$ai_pw" pg_dump -h 127.0.0.1 -U "$ai_user" -Fc -d "$db_ai" -f "$dir/ai.dump" || return 1
  pg_restore -l "$dir/spring.dump" >/dev/null || return 1
  pg_restore -l "$dir/ai.dump" >/dev/null || return 1
  printf '{"takenAt":"%s","springDb":"%s","aiDb":"%s"}\n' "$(now)" "$db_spring" "$db_ai" > "$dir/meta.json"
  say "双库备份完成：$dir"
}

write_state() { # write_state <rel> <previous>
  local rel="$1" prev="$2"
  python3 - "$STATE/current.json" "$rel" "$prev" "$(now)" <<'PY'
import json, sys
path, rel, prev, ts = sys.argv[1:5]
data = {"release": rel, "previous": prev, "activatedAt": ts}
json.dump(data, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
  chmod 0640 "$STATE/current.json"
}

current_release() {
  [ -L "$CUR" ] || return 0
  basename "$(readlink -f "$CUR")"
}

verify_release() { # verify_release <dir>
  local dir="$1"
  [ -f "$dir/SHA256SUMS" ] || die "缺少 SHA256SUMS"
  ( cd "$dir" && sha256sum -c --quiet SHA256SUMS ) || die "产物校验失败"
  for f in spring.jar web.tar.gz ai.tar.gz manifest.json; do
    [ -f "$dir/$f" ] || die "缺少产物 $f"
  done
}

materialize() { # materialize <dir>
  local dir="$1"
  if [ ! -d "$dir/web" ] || [ ! -f "$dir/spring/app.jar" ]; then
    install -d -m 0755 "$dir/spring" "$dir/web" "$dir/ai"
    install -m 0644 "$dir/spring.jar" "$dir/spring/app.jar"
    tar -xzf "$dir/web.tar.gz" -C "$dir/web"
    tar -xzf "$dir/ai.tar.gz" -C "$dir/ai"
    say "产物已展开：$dir"
  fi
  # 两种 Python 交付形态：
  #   1) 产物自带 .venv（本机构建）；
  #   2) 产物带 wheels + requirements.txt（CI 构建，服务器离线安装，架构与 Runner 一致）。
  if [ ! -x "$dir/ai/.venv/bin/python" ]; then
    if [ -f "$dir/ai/requirements.txt" ] && [ -d "$dir/ai/wheels" ]; then
      say "离线创建 Python 虚拟环境"
      ( cd "$dir/ai" && uv venv --python 3.12 .venv \
        && uv pip install --python .venv/bin/python --no-index --find-links wheels -r requirements.txt ) \
        || die "Python 依赖安装失败"
    else
      die "Python 依赖缺失（既无 .venv 也无 wheels）"
    fi
  fi
  chown -R mai-deploy:mai-deploy "$dir"
}

activate() { # activate <rel>
  local rel="$1" dir="$BASE/releases/$rel" prev
  prev=$(current_release)
  if [ "$prev" = "$rel" ]; then
    # current 已指向目标版本（上次在启动阶段失败）：不重复备份，直接拉起并做就绪检查。
    say "current 已是 $rel，执行启动与就绪检查"
    start_ai && start_spring && start_web || return 1
    svc_wait "https://127.0.0.1/api/v1/profile" 30 "Nginx to Spring" || return 1
    local kept
    kept=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("previous") or "")' "$STATE/current.json" 2>/dev/null || true)
    write_state "$rel" "$kept"
    say "服务已就绪：$rel"
    return 0
  fi

  stop_all
  local tag
  tag="deploy-$(date -u +%Y%m%dT%H%M%SZ)-$rel"
  backup_dbs "$tag" || { start_ai; start_spring; start_web; die "备份失败，未切换"; }

  # Alembic 必须在切换后、启动前执行；失败保留日志。
  ln -sfn "$dir" "$CUR"
  chown -h mai-deploy:mai-deploy "$CUR"
  say "已切换 current -> $rel（备份：$tag）"

  # 注意：! 必须作用于整个子 shell，否则只否定 set -a，成功也会被判失败。
  if ! ( set -a; . "$ENV_AI"; set +a;
       cd "$CUR/ai" && "$CUR/ai/.venv/bin/python" -m alembic upgrade head ) \
       > "$LOGDIR/ai/alembic.log" 2>&1; then
    say "Alembic 迁移失败，日志：$LOGDIR/ai/alembic.log"
    return 1
  fi

  start_ai || return 1
  start_spring || return 1
  start_web || return 1
  svc_wait "https://127.0.0.1/api/v1/profile" 30 "Nginx→Spring" || return 1
  write_state "$rel" "$prev"
  say "部署完成：$rel（上一版 $prev）"
}

rollback() {
  local prev
  prev=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("previous") or "")' "$STATE/current.json" 2>/dev/null || true)
  [ -n "$prev" ] && [ -d "$BASE/releases/$prev" ] || die "没有可回退的上一版"
  say "回退到 $prev（数据库不做自动回退；结构变更需 restore-db）"
  stop_all
  ln -sfn "$BASE/releases/$prev" "$CUR"
  start_ai && start_spring && start_web || die "回退启动失败，保持停机"
  write_state "$prev" ""
  say "程序已回退：$prev"
}

restore_db() { # restore_db <backup-dir>
  local dir="$1"
  [ -d "$dir" ] || die "备份目录不存在：$dir"
  local spring_user spring_pass ai_url db_spring db_ai ai_user ai_pw
  spring_user=$(env_get "$ENV_SPRING" SPRING_DATASOURCE_DRUID_USERNAME)
  spring_pass=$(env_get "$ENV_SPRING" SPRING_DATASOURCE_DRUID_PASSWORD)
  db_spring=$(env_get "$ENV_SPRING" SPRING_DATASOURCE_DRUID_URL | sed -E 's|.*/([^/?]+).*|\1|')
  ai_url=$(env_get "$ENV_AI" DATABASE_URL)
  db_ai=$(printf '%s' "$ai_url" | sed -E 's|.*/([^/?]+).*|\1|')
  ai_user=$(printf '%s' "$ai_url" | sed -E 's|^[^:]+://([^:]+):.*@.*|\1|')
  ai_pw=$(printf '%s' "$ai_url" | sed -E 's|^[^:]+://[^:]+:([^@]+)@.*|\1|')
  stop_all
  PGPASSWORD="$spring_pass" pg_restore -h 127.0.0.1 -U "$spring_user" -d "$db_spring" --clean --if-exists "$dir/spring.dump" || die "Spring 库恢复失败"
  PGPASSWORD="$ai_pw" pg_restore -h 127.0.0.1 -U "$ai_user" -d "$db_ai" --clean --if-exists "$dir/ai.dump" || die "AI 库恢复失败"
  say "双库已恢复：$dir"
}

status() {
  local rel; rel=$(current_release)
  printf 'release=%s\n' "${rel:-<none>}"
  [ -f "$STATE/current.json" ] && cat "$STATE/current.json"
  for s in nginx postgresql redis-server $COMPOSE_SERVICES; do
    printf '%-22s %s\n' "$s" "$(systemctl is-active "$s" 2>/dev/null || true)"
  done
  printf 'spring  %s\n' "$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 3 https://127.0.0.1/api/v1/profile || true)"
  printf 'web     %s\n' "$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 3 https://127.0.0.1/ || true)"
  printf 'ai      %s\n' "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -H "Authorization: Bearer $(env_get "$ENV_AI" AI_SERVER_TOKEN)" http://127.0.0.1:9344/internal/v1/health/ready || true)"
}

case "${1:-}" in
  deploy)
    [ $# -eq 2 ] || die "用法：mai-portfolio-deploy deploy <release>"
    rel="$2"
    case "$rel" in *[!A-Za-z0-9._-]*) die "非法 release 名" ;; esac
    dir="$BASE/releases/$rel"
    [ -d "$dir" ] || die "release 不存在：$dir"
    verify_release "$dir"
    materialize "$dir"
    if ! activate "$rel"; then
      say "启动失败，尝试回退程序"
      rollback || say "回退未完成，需人工介入"
      exit 1
    fi
    # 只保留最近三个版本，避免 30G 盘被占满。
    ls -1dt "$REL"/*/ 2>/dev/null | tail -n +4 | while read -r old; do
      [ "$(readlink -f "$old")" = "$(readlink -f "$CUR")" ] && continue
      rm -rf "$old"
    done
    ;;
  rollback) rollback ;;
  restore-db)
    [ $# -eq 2 ] || die "用法：mai-portfolio-deploy restore-db <backup-dir>"
    restore_db "$2" ;;
  status) status ;;
  *) die "用法：deploy <release> | rollback | restore-db <dir> | status" ;;
esac
