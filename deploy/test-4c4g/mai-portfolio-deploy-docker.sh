#!/usr/bin/env bash
# 根权限部署助手（Docker 版）。
# 安装位置：/usr/local/sbin/mai-portfolio-deploy（root:root 0755）
# 职责：校验产物 → 构建镜像 → 双库备份 → 串行停机 → Alembic → 切换 → 就绪检查 → 记录 → 失败回退。
# 中间件（PostgreSQL 16 / Redis / Nginx）由宿主机管理，本脚本不改动它们。
set -uo pipefail

BASE=/srv/mai-portfolio
REL="$BASE/releases"
CUR="$BASE/current"
STATE="$BASE/shared/state"
ENV_SPRING="$BASE/shared/env/spring.env"
ENV_AI="$BASE/shared/env/ai.env"
LOGDIR="$BASE/shared/logs"
BACKUPS="$BASE/shared/backups"
ETC=/etc/mai-portfolio
COMPOSE="$ETC/docker-compose.yml"

die() { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }
say() { printf '[deploy] %s\n' "$*"; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
env_get() { awk -F= -v k="$2" '$1==k { sub(/^[^=]*=/, ""); print; exit }' "$1"; }

svc_wait() {
  local url="$1" limit="$2" name="$3" i=0 code
  while [ "$i" -lt "$limit" ]; do
    code=$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 3 "$url" || true)
    case "$code" in 200|204) say "$name 就绪（HTTP $code）"; return 0 ;; esac
    i=$((i + 1)); sleep 1
  done
  return 1
}

down_stack() {
  docker compose -f "$COMPOSE" down --remove-orphans >/dev/null 2>&1 || true
  sleep 1
}

up_stack() { RELEASE="$1" docker compose -f "$COMPOSE" up -d --remove-orphans; }

build_images() {
  local dir="$1" rel="$2"
  say "构建镜像 $rel"
  docker build -q -f "$ETC/spring.Dockerfile" -t "mai-portfolio-spring:$rel" "$dir" >/dev/null || return 1
  docker build -q -f "$ETC/web.Dockerfile" -t "mai-portfolio-web:$rel" "$dir" >/dev/null || return 1
  docker build -q -f "$ETC/ai.Dockerfile" -t "mai-portfolio-ai:$rel" "$dir" >/dev/null || return 1
  say "镜像构建完成"
}

backup_dbs() {
  local dir="$BACKUPS/$1"
  install -d -m 0750 "$dir"
  local spring_url spring_user spring_pass ai_url db_spring db_ai ai_user ai_pw
  spring_url=$(env_get "$ENV_SPRING" SPRING_DATASOURCE_DRUID_URL)
  spring_user=$(env_get "$ENV_SPRING" SPRING_DATASOURCE_DRUID_USERNAME)
  spring_pass=$(env_get "$ENV_SPRING" SPRING_DATASOURCE_DRUID_PASSWORD)
  ai_url=$(env_get "$ENV_AI" DATABASE_URL)
  db_spring=$(printf '%s' "$spring_url" | sed -E 's|.*/([^/?]+).*|\1|')
  db_ai=$(printf '%s' "$ai_url" | sed -E 's|.*/([^/?]+).*|\1|')
  ai_user=$(printf '%s' "$ai_url" | sed -E 's|^[^:]+://([^:]+):.*@.*|\1|')
  ai_pw=$(printf '%s' "$ai_url" | sed -E 's|^[^:]+://[^:]+:([^@]+)@.*|\1|')
  PGPASSWORD="$spring_pass" pg_dump -h 127.0.0.1 -U "$spring_user" -Fc -d "$db_spring" -f "$dir/spring.dump" || return 1
  PGPASSWORD="$ai_pw" pg_dump -h 127.0.0.1 -U "$ai_user" -Fc -d "$db_ai" -f "$dir/ai.dump" || return 1
  pg_restore -l "$dir/spring.dump" >/dev/null || return 1
  pg_restore -l "$dir/ai.dump" >/dev/null || return 1
  printf '{"takenAt":"%s","springDb":"%s","aiDb":"%s"}\n' "$(now)" "$db_spring" "$db_ai" > "$dir/meta.json"
  say "双库备份完成：$dir"
}

write_state() {
  python3 - "$STATE/current.json" "$1" "$2" "$(now)" <<'PY'
import json, sys
path, rel, prev, ts = sys.argv[1:5]
json.dump({"release": rel, "previous": prev, "activatedAt": ts},
          open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
PY
  chmod 0640 "$STATE/current.json"
}

current_release() { [ -L "$CUR" ] && basename "$(readlink -f "$CUR")" || true; }

verify_release() {
  local dir="$1"
  [ -f "$dir/SHA256SUMS" ] || die "缺少 SHA256SUMS"
  ( cd "$dir" && sha256sum -c --quiet SHA256SUMS ) || die "产物校验失败"
  for f in spring.jar web.tar.gz ai.tar.gz manifest.json; do [ -f "$dir/$f" ] || die "缺少产物 $f"; done
}

materialize() {
  local dir="$1"
  if [ ! -d "$dir/web/.output" ] || [ ! -d "$dir/ai/app" ]; then
    tar -xzf "$dir/web.tar.gz" -C "$dir/web" 2>/dev/null || {
      install -d -m 0755 "$dir/web"; tar -xzf "$dir/web.tar.gz" -C "$dir/web"; }
    install -d -m 0755 "$dir/ai"
    tar -xzf "$dir/ai.tar.gz" -C "$dir/ai"
    say "产物已展开：$dir"
  fi
  chown -R mai-deploy:mai-deploy "$dir"
}

migrate_ai() {
  local rel="$1"
  docker run --rm --network host --env-file "$ENV_AI" "mai-portfolio-ai:$rel" \
    python -m alembic upgrade head > "$LOGDIR/ai/alembic.log" 2>&1 || {
      say "Alembic 迁移失败，日志：$LOGDIR/ai/alembic.log"
      return 1
    }
  say "Alembic 迁移完成"
}

start_all() {
  local rel="$1"
  up_stack "$rel" || return 1
  svc_wait "http://127.0.0.1:9344/internal/v1/health/live" 90 "Python" || return 1
  svc_wait "http://127.0.0.1:9333/api/v1/profile" 180 "Spring" || return 1
  svc_wait "http://127.0.0.1:3000/" 90 "Nuxt" || return 1
  svc_wait "https://127.0.0.1/api/v1/profile" 30 "Nginx→Spring" || return 1
  svc_wait "https://127.0.0.1/" 30 "Nginx→Nuxt" || return 1
}

activate() {
  local rel="$1" dir="$BASE/releases/$rel" prev kept tag
  prev=$(current_release)
  if [ "$prev" = "$rel" ]; then
    say "current 已是 $rel，执行启动与就绪检查"
    start_all "$rel" || return 1
    kept=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("previous") or "")' \
        "$STATE/current.json" 2>/dev/null || true)
    write_state "$rel" "$kept"
    say "服务已就绪：$rel"
    return 0
  fi

  build_images "$dir" "$rel" || return 1

  tag="deploy-$(date -u +%Y%m%dT%H%M%SZ)-$rel"
  down_stack
  backup_dbs "$tag" || { [ -n "$prev" ] && start_all "$prev"; die "备份失败，未切换"; }

  ln -sfn "$dir" "$CUR"
  chown -h mai-deploy:mai-deploy "$CUR"
  say "已切换 current -> $rel（备份：$tag）"

  migrate_ai "$rel" || return 1
  start_all "$rel" || return 1
  write_state "$rel" "$prev"
  say "部署完成：$rel（上一版 $prev）"
}

rollback() {
  local prev
  prev=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("previous") or "")' \
      "$STATE/current.json" 2>/dev/null || true)
  [ -n "$prev" ] && [ -d "$BASE/releases/$prev" ] || die "没有可回退的上一版"
  say "回退到 $prev（数据库不自动回退；结构变更需 restore-db）"
  down_stack
  ln -sfn "$BASE/releases/$prev" "$CUR"
  start_all "$prev" || die "回退启动失败，保持停机"
  write_state "$prev" ""
  say "程序已回退：$prev"
}

restore_db() {
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
  down_stack
  PGPASSWORD="$spring_pass" pg_restore -h 127.0.0.1 -U "$spring_user" -d "$db_spring" --clean --if-exists "$dir/spring.dump" || die "Spring 库恢复失败"
  PGPASSWORD="$ai_pw" pg_restore -h 127.0.0.1 -U "$ai_user" -d "$db_ai" --clean --if-exists "$dir/ai.dump" || die "AI 库恢复失败"
  say "双库已恢复：$dir"
}

status() {
  local rel; rel=$(current_release)
  printf 'release=%s\n' "${rel:-<none>}"
  [ -f "$STATE/current.json" ] && cat "$STATE/current.json"
  printf '\n容器：\n'; docker ps --filter "name=mai-portfolio-" --format '  {{.Names}}  {{.Status}}  {{.Image}}'
  printf '中间件：\n'
  for s in nginx postgresql redis-server docker; do printf '  %-14s %s\n' "$s" "$(systemctl is-active "$s" 2>/dev/null || true)"; done
  printf '探活：spring=%s web=%s ai=%s\n' \
    "$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 3 https://127.0.0.1/api/v1/profile || true)" \
    "$(curl -s -k -o /dev/null -w '%{http_code}' --max-time 3 https://127.0.0.1/ || true)" \
    "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:9344/internal/v1/health/live || true)"
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
    ls -1dt "$REL"/*/ 2>/dev/null | tail -n +4 | while read -r old; do
      [ "$(readlink -f "$old")" = "$(readlink -f "$CUR")" ] && continue
      n=$(basename "$old")
      rm -rf "$old"
      docker image rm -f "mai-portfolio-spring:$n" "mai-portfolio-web:$n" "mai-portfolio-ai:$n" >/dev/null 2>&1 || true
    done
    ;;
  rollback) rollback ;;
  restore-db)
    [ $# -eq 2 ] || die "用法：mai-portfolio-deploy restore-db <backup-dir>"
    restore_db "$2" ;;
  status) status ;;
  *) die "用法：deploy <release> | rollback | restore-db <dir> | status" ;;
esac
