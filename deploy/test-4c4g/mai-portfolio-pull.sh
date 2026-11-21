#!/usr/bin/env bash
# 测试机侧发布拉取器（以 mai-deploy 身份运行，无需 root）。
#
# 职责：从 GitHub Release 主动出站下载已批准的产物 → 校验 → 交给受控部署脚本。
#
# 信任模型：
#   * 权威校验值取自 GitHub Releases API 的 asset.digest（sha256），API 走 TLS；
#   * 大文件走国内镜像 + 多线程加速，只用于传输，内容以 digest 为准，被替换即失败；
#   * 仓库公开时不需要任何凭据；私有化后可放「只读」token 到
#     /srv/mai-portfolio/shared/env/github-read.token（绝不能放写权限令牌）。
#
# 用法：
#   mai-portfolio-pull                 # 拉取最新 test4c4g-* 发布并部署
#   mai-portfolio-pull <tag>           # 拉取指定 tag 并部署
#   mai-portfolio-pull <tag> --no-activate
set -euo pipefail

REPO="${MAI_REPO:-WKING66/mai-s-portfolio}"
MIRRORS="${MAI_GH_MIRRORS-https://ghfast.top https://gh-proxy.com https://ghproxy.net}"
BASE=/srv/mai-portfolio
REL="$BASE/releases"
LOCK="$BASE/tmp/pull.lock"
TOKEN_FILE="$BASE/shared/env/github-read.token"
TOKEN="${GITHUB_READ_TOKEN:-}"
[ -z "$TOKEN" ] && [ -r "$TOKEN_FILE" ] && TOKEN=$(tr -d '\r\n' < "$TOKEN_FILE")

say() { printf '[pull] %s\n' "$*"; }
die() { printf '[pull] ERROR: %s\n' "$*" >&2; exit 1; }

# 同一时刻只允许一个拉取进程：两个 aria2 同时写同一文件会把内容写花。
exec 9>"$LOCK"
flock -n 9 || die "已有拉取进程在运行（$LOCK）"

activate=true
tag=""
for arg in "$@"; do
  case "$arg" in
    --no-activate) activate=false ;;
    -*) die "未知参数：$arg" ;;
    *) tag="$arg" ;;
  esac
done

api() {
  if [ -n "$TOKEN" ]; then
    curl -fsSL -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "https://api.github.com$1"
  else
    curl -fsSL -H "Accept: application/vnd.github+json" "https://api.github.com$1"
  fi
}

if [ -z "$tag" ]; then
  say "解析最新 test4c4g 发布"
  tag=$( api "/repos/$REPO/releases?per_page=30" \
    | python3 -c 'import json,sys
rel=[r for r in json.load(sys.stdin) if r.get("tag_name","").startswith("test4c4g-")]
rel.sort(key=lambda r: r.get("created_at",""), reverse=True)
print(rel[0]["tag_name"] if rel else "")' ) || die "查询发布失败"
  [ -n "$tag" ] || die "没有可用的 test4c4g-* 发布"
fi

sha="${tag#test4c4g-}"
case "$sha" in *[!0-9a-f]*) die "tag 不是 test4c4g-<sha> 形式：$tag" ;; esac

dest="$REL/$sha"
incoming="$dest/incoming"
rm -rf "$incoming"
install -d -m 0755 "$dest" "$incoming"

say "读取发布元数据（$tag）"
api "/repos/$REPO/releases/tags/$tag" > "$incoming/.release.json" || die "读取发布失败"
# 输出 "<名字> <大小> <sha256>"，每行一个产物。
python3 - "$incoming/.release.json" > "$incoming/.assets.txt" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
for a in d.get("assets", []):
    digest = (a.get("digest") or "")
    if not digest.startswith("sha256:"):
        continue
    print(a["name"], a["size"], digest.split(":", 1)[1])
PY
[ -s "$incoming/.assets.txt" ] || die "发布元数据没有可用 digest，无法校验"

expected_hash() { awk -v a="$1" '$1==a {print $3}' "$incoming/.assets.txt"; }
expected_size() { awk -v a="$1" '$1==a {print $2}' "$incoming/.assets.txt"; }
has_asset() { awk -v a="$1" '$1==a {f=1} END {exit !f}' "$incoming/.assets.txt"; }

verify_one() {
  local asset="$1" want size got
  want=$(expected_hash "$asset")
  size=$(expected_size "$asset")
  [ -f "$incoming/$asset" ] || return 1
  [ "$(stat -c%s "$incoming/$asset")" = "$size" ] || return 1
  got=$(sha256sum "$incoming/$asset" | awk '{print $1}')
  [ "$got" = "$want" ]
}

fetch() { # fetch <asset>：多镜像、多轮、单进程；成功与否只看 digest。
  local asset="$1" direct="$direct_base/$asset" round u urls=()
  for m in $MIRRORS; do urls+=("$m/$direct"); done
  urls+=("$direct")

  for round in 1 2 3; do
    for u in "${urls[@]}"; do
      rm -f "$incoming/$asset" "$incoming/$asset.aria2"
      if command -v aria2c >/dev/null 2>&1; then
        timeout 900 aria2c -q -x16 -s16 -k1M --max-tries=2 --retry-wait=2           --check-integrity=false --console-log-level=error --summary-interval=0           --dir="$incoming" --out="$asset" --allow-overwrite=true "$u" || true
        rm -f "$incoming/$asset.aria2"
      else
        curl -fsSL --retry 2 --connect-timeout 10 -o "$incoming/$asset" "$u" || true
      fi
      if verify_one "$asset"; then
        say "已下载并校验：$asset"
        return 0
      fi
      say "第 $round 轮未通过，换下一个地址"
    done
  done
  die "$asset 下载失败或内容与发布 digest 不一致"
}

direct_base="https://github.com/$REPO/releases/download/$tag"
say "下载 $tag（镜像：$MIRRORS 及直连）"

if [ -n "$TOKEN" ]; then
  for asset in spring.jar web.tar.gz ai.tar.gz manifest.json SHA256SUMS; do
    has_asset "$asset" || die "发布中缺少 $asset"
    asset_id=$(python3 - "$incoming/.release.json" "$asset" <<'PY'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
print(next((a["id"] for a in d.get("assets", []) if a["name"] == sys.argv[2]), ""))
PY
)
    curl -fsSL -H "Authorization: Bearer $TOKEN" -H "Accept: application/octet-stream" \
      -o "$incoming/$asset" "https://api.github.com/repos/$REPO/releases/assets/$asset_id" \
      || die "下载 $asset 失败"
    verify_one "$asset" || die "$asset 校验失败"
  done
else
  for asset in spring.jar web.tar.gz ai.tar.gz manifest.json SHA256SUMS; do
    has_asset "$asset" || die "发布中缺少 $asset"
    fetch "$asset"
  done
fi

say "整体校验"
( cd "$incoming" && sha256sum -c --quiet SHA256SUMS ) || die "SHA256SUMS 与产物不一致"
for f in spring.jar web.tar.gz ai.tar.gz manifest.json SHA256SUMS; do mv -f "$incoming/$f" "$dest/$f"; done
rm -rf "$incoming"

say "已落盘 $dest"
python3 - "$dest/manifest.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8"))
print("gitSha=%s cdManaged=%s builtBy=%s" % (m.get("gitSha"), m.get("cdManaged"), m.get("builtBy")))
PY

if [ "$activate" = true ]; then
  say "交给受控部署脚本（串行停机、双库备份、就绪检查）"
  sudo /usr/local/sbin/mai-portfolio-deploy deploy "$sha"
else
  say "已跳过部署（--no-activate）"
fi
