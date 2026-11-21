#!/usr/bin/env bash
# 测试机侧发布拉取器（以 mai-deploy 身份运行，无需 root）。
#
# 职责：从 GitHub Release 主动出站下载已批准的产物 → 校验 → 交给受控部署脚本。
# 设计：服务器只保存「只读」凭据（可选）；仓库公开时完全匿名下载。
# 用法：
#   mai-portfolio-pull                 # 拉取最新 test4c4g-* 发布并部署
#   mai-portfolio-pull <tag>           # 拉取指定 tag 并部署
#   mai-portfolio-pull <tag> --no-activate
set -euo pipefail

REPO="${MAI_REPO:-WKING66/mai-s-portfolio}"
BASE=/srv/mai-portfolio
REL="$BASE/releases"
TOKEN_FILE="$BASE/shared/env/github-read.token"
TOKEN="${GITHUB_READ_TOKEN:-}"
[ -z "$TOKEN" ] && [ -r "$TOKEN_FILE" ] && TOKEN=$(tr -d '\r\n' < "$TOKEN_FILE")

say() { printf '[pull] %s\n' "$*"; }
die() { printf '[pull] ERROR: %s\n' "$*" >&2; exit 1; }

activate=true
tag=""
for arg in "$@"; do
  case "$arg" in
    --no-activate) activate=false ;;
    -*) die "未知参数：$arg" ;;
    *) tag="$arg" ;;
  esac
done

api() { # api <path>
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
install -d -m 0755 "$dest" "$incoming"

say "下载 $tag 到 $incoming"
for asset in spring.jar web.tar.gz ai.tar.gz manifest.json SHA256SUMS; do
  if [ -n "$TOKEN" ]; then
    asset_id=$(api "/repos/$REPO/releases/tags/$tag" \
      | python3 -c "import json,sys
d=json.load(sys.stdin)
print(next((a['id'] for a in d.get('assets',[]) if a['name']=='$asset'),''))")
    [ -n "$asset_id" ] || die "发布中缺少 $asset"
    curl -fsSL -H "Authorization: Bearer $TOKEN" -H "Accept: application/octet-stream" \
      -o "$incoming/$asset" "https://api.github.com/repos/$REPO/releases/assets/$asset_id"
  else
    curl -fsSL -o "$incoming/$asset" \
      "https://github.com/$REPO/releases/download/$tag/$asset" || die "下载 $asset 失败"
  fi
done

say "校验 SHA256"
( cd "$incoming" && sha256sum -c --quiet SHA256SUMS ) || die "校验失败，拒绝部署"
for f in spring.jar web.tar.gz ai.tar.gz manifest.json; do mv -f "$incoming/$f" "$dest/$f"; done
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
