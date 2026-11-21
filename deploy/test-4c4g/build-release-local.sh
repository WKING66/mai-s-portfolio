#!/usr/bin/env bash
# 一次性引导构建（非 CD 路径）。
# 用途：在尚未接通 GitHub Actions 时，于本机按 dev 提交构建出与 CD 相同的产物布局。
# 产物目录结构与 CD 发布物一致：<release>/{spring.jar,web.tar.gz,ai.tar.gz,manifest.json,SHA256SUMS}
set -uo pipefail

SHA="${1:?用法：build-release-local.sh <git-sha>}"
BASE=/srv/mai-portfolio
STAGE="$BASE/tmp/stage-$SHA"
LOGD="$BASE/tmp/buildlogs-$SHA"
REL="$BASE/releases/$SHA"
SRC="$BASE/tmp/mai-src-$SHA.tar.gz"

mkdir -p "$LOGD"
step() { printf '[build] %s %s\n' "$(date -u +%H:%M:%SZ)" "$*" | tee -a "$LOGD/pipeline.log"; }
fail() { printf '[build] FAIL %s\n' "$*" | tee -a "$LOGD/pipeline.log"; exit 1; }

if [ ! -d "$STAGE" ]; then
  step "解压源码"
  mkdir -p "$STAGE"
  tar -xzf "$SRC" -C "$STAGE" || fail "解压失败"
fi

step "Maven 构建 Spring"
( cd "$STAGE/mai-portfolio-backend" && MAVEN_OPTS="-Xmx1g" \
    mvn -B -q -Dmaven.test.skip=true package -pl mai-portfolio-launch -am ) > "$LOGD/01-spring.log" 2>&1 \
  || fail "Spring 构建失败，见 $LOGD/01-spring.log"
JAR=$(find "$STAGE/mai-portfolio-backend/mai-portfolio-launch/target" -maxdepth 1 -name '*.jar' ! -name '*.original' | head -1)
[ -n "$JAR" ] || fail "未找到 Spring 产物"

step "pnpm 安装并构建 Nuxt"
(
  cd "$STAGE/mai-portfolio-frontend" || exit 1
  if [ ! -f pnpm-workspace.yaml ]; then
    pnpm config set --location=project --json allowBuilds '{"esbuild":false,"unrs-resolver":false,"vue-demi":false}' >/dev/null
  fi
  pnpm install --frozen-lockfile && pnpm build
) > "$LOGD/02-web.log" 2>&1 || fail "Nuxt 构建失败，见 $LOGD/02-web.log"
[ -f "$STAGE/mai-portfolio-frontend/.output/server/index.mjs" ] || fail "未找到 Nuxt 产物"

step "uv 安装 Python 依赖"
( cd "$STAGE/mai-portfolio-ai-server" && UV_DEFAULT_INDEX=https://mirrors.aliyun.com/pypi/simple \
    uv sync --frozen --python 3.12 ) > "$LOGD/03-ai.log" 2>&1 \
  || fail "Python 依赖安装失败，见 $LOGD/03-ai.log"
[ -x "$STAGE/mai-portfolio-ai-server/.venv/bin/python" ] || fail "未生成 Python 虚拟环境"

step "组装 release $SHA"
install -d -m 0755 "$REL"
install -m 0644 "$JAR" "$REL/spring.jar"
tar -czf "$REL/web.tar.gz" -C "$STAGE/mai-portfolio-frontend" .output
tar -czf "$REL/ai.tar.gz" -C "$STAGE/mai-portfolio-ai-server" \
  --exclude='__pycache__' --exclude='.pytest_cache' --exclude='.ruff_cache' app migrations alembic.ini pyproject.toml uv.lock .venv

GIT_SHORT=$(printf '%s' "$SHA" | cut -c1-12)
cat > "$REL/manifest.json" <<JSON
{
  "gitSha": "$SHA",
  "gitShort": "$GIT_SHORT",
  "builtAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "builtBy": "server-local-bootstrap",
  "cdManaged": false,
  "note": "非 CD 产物：GitHub Actions 未接通前的一次性本机构建，仅用于环境验收。",
  "versions": {
    "java": "$(java -version 2>&1 | head -1)",
    "node": "$(node -v)",
    "pnpm": "$(pnpm -v)",
    "python": "$(python3 -V 2>&1)",
    "uv": "$(uv --version | awk '{print $2}')",
    "postgresql": "$(psql --version | awk '{print $3}')",
    "redis": "$(redis-server --version | sed -E 's/.*v=([0-9.]+).*/\1/')"
  }
}
JSON
( cd "$REL" && sha256sum spring.jar web.tar.gz ai.tar.gz manifest.json > SHA256SUMS )
chown -R mai-deploy:mai-deploy "$REL"
step "完成：$REL"
ls -lh "$REL"
