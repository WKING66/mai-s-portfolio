#!/usr/bin/env bash
set -euo pipefail
umask 077
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
unset DOCKER_HOST DOCKER_CONTEXT DOCKER_CONFIG HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy
cd /

repo=WKING66/mai-s-portfolio
config=/etc/mai-portfolio
shared=/srv/mai-portfolio/shared
state=/var/lib/mai-portfolio-deploy
assets=(spring.jar web.tar.gz ai.tar.gz manifest.json SHA256SUMS)

die() { printf '[deploy] %s\n' "$*" >&2; exit 1; }
valid_tag() { [[ "$1" =~ ^test4c4g-[0-9a-f]{12}$ ]]; }

[[ $EUID == 0 ]] || die "Run through the configured sudo entry."
install -d -m 0700 "$state" "$state/staging" "$state/backups"
exec 9>"$state/deploy.lock"
flock -n 9 || die "Another deployment is running."

compose() {
  local tag=$1
  shift
  RELEASE="${tag#test4c4g-}" SPRING_UID="$(id -u mai-spring)" SPRING_GID="$(id -g mai-spring)" \
    docker compose --project-name mai-portfolio --env-file /dev/null -f "$config/docker-compose.yml" "$@"
}

wait_http() {
  local url=$1 timeout_seconds=$2
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if curl -q --fail --silent --output /dev/null --max-time 3 "$url"; then
      return 0
    fi
    sleep 1
  done
  printf '[deploy] Readiness timed out for %s\n' "$url" >&2
  return 1
}

wait_ai() {
  local tag=$1 deadline=$((SECONDS + 60))
  while (( SECONDS < deadline )); do
    if compose "$tag" exec -T ai python -c '
import os
import urllib.request
request = urllib.request.Request("http://127.0.0.1:9344/internal/v1/health/ready",
    headers={"Authorization": "Bearer " + os.environ["AI_SERVER_TOKEN"]})
with urllib.request.urlopen(request, timeout=3) as response:
    if response.status != 200:
        raise SystemExit(1)
' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  printf '[deploy] Authenticated AI readiness timed out.\n' >&2
  return 1
}

wait_stack() {
  wait_ai "$1" &&
    wait_http http://127.0.0.1:9333/api/v1/profile 150 &&
    wait_http http://127.0.0.1:3000/ 60 &&
    curl -q --fail --silent --show-error --max-time 5 --cacert "$shared/secrets/tls/server.crt" \
      --output /dev/null https://127.0.0.1/api/v1/profile &&
    curl -q --fail --silent --show-error --max-time 5 --cacert "$shared/secrets/tls/server.crt" \
      --output /dev/null https://127.0.0.1/
}

stop_stack() {
  compose "$1" down --timeout 45 || return 1
  local listeners
  listeners=$(ss -H -ltn '( sport = :3000 or sport = :9333 or sport = :9344 )') || return 1
  [[ -z "$listeners" ]]
}

github_json() {
  curl -q --noproxy '*' --fail --silent --show-error --proto '=https' --connect-timeout 10 --max-time 40 \
    -H 'Accept: application/vnd.github+json' "https://api.github.com/repos/$repo/$1"
}

asset_matches() {
  local path=$1 size=$2 digest=$3
  [[ -f "$path" && $(stat -c %s "$path") == "$size" ]] &&
    printf '%s  %s\n' "$digest" "$path" | sha256sum --check --status
}

download_asset() {
  local path=$1 size=$2 digest=$3 url=$4
  shift 4
  curl -q "$@" --no-insecure --no-proxy-insecure --fail --silent --show-error --location \
    --proto '=https' --proto-redir '=https' --connect-timeout 10 --max-time 1200 \
    --max-filesize "$size" --output "$path" "$url" &&
    asset_matches "$path" "$size" "$digest"
}

stage_release() {
  local tag=$1 dir=$2 sha asset size digest destination url
  local transfer=()
  # Optional root-owned curl config for the asset transfer only. Metadata always goes to GitHub.
  if [[ -f "$config/github-transfer.curl" ]]; then
    transfer=(--config "$config/github-transfer.curl")
  fi
  github_json "releases/tags/$tag" > "$dir/release.json"
  github_json "commits/$tag" > "$dir/commit.json"
  sha=$(jq -er --arg tag "$tag" \
    '.sha | select(test("^[0-9a-f]{40}$") and ("test4c4g-" + .[0:12] == $tag))' "$dir/commit.json")
  jq -e --arg tag "$tag" --arg sha "$sha" \
    '.tag_name == $tag and .target_commitish == $sha and .draft == false and .published_at != null' \
    "$dir/release.json" >/dev/null
  for asset in "${assets[@]}"; do
    read -r size digest < <(jq -er --arg name "$asset" '
      [.assets[] | select(.name == $name)] | select(length == 1) | .[0]
      | select(.state == "uploaded" and .size > 0 and .size <= 536870912)
      | select(.digest | test("^sha256:[0-9a-f]{64}$"))
      | [.size, (.digest | sub("^sha256:"; ""))] | @tsv' "$dir/release.json") || die "Missing GitHub digest or invalid size for $asset."
    # For a slow link, mai-deploy can upload the same public asset over SSH.
    # Only mai-deploy opens its own files. Root uses a private copy and trusts the GitHub digest.
    if timeout 30s runuser -u mai-deploy -- head -c "$((size + 1))" -- \
        "/srv/mai-portfolio/incoming/$tag/$asset" > "$dir/$asset" 2>/dev/null &&
        asset_matches "$dir/$asset" "$size" "$digest"; then
      continue
    fi
    destination="$dir/$asset"
    url="https://github.com/$repo/releases/download/$tag/$asset"
    if (( ${#transfer[@]} )); then
      if download_asset "$destination" "$size" "$digest" "$url" \
        "${transfer[@]}" --retry 0 --speed-limit 8192 --speed-time 15; then
        continue
      fi
      printf '[deploy] Proxy download failed or did not match GitHub for %s. Trying direct for this release.\n' "$asset" >&2
      transfer=()
      rm -f -- "$destination"
    fi
    download_asset "$destination" "$size" "$digest" "$url" --noproxy '*' --retry 2 ||
      die "Direct download failed or GitHub size or digest mismatch for $asset."
  done
  jq -e --arg sha "$sha" --arg tag "$tag" \
    '.gitSha == $sha and .tag == $tag and .cdManaged == true and .builtBy == "github-actions" and .arch == "x86_64"' \
    "$dir/manifest.json" >/dev/null
  # Compare the CI checksum file as data. Do not interpret arbitrary paths from it.
  (cd "$dir"; sha256sum spring.jar web.tar.gz ai.tar.gz manifest.json | cmp -s - SHA256SUMS)
}

build_images() {
  local dir=$1 tag=$2 service artifact
  for service in spring web ai; do
    case "$service" in
      spring) artifact=spring.jar ;;
      web) artifact=web.tar.gz ;;
      ai) artifact=ai.tar.gz ;;
    esac
    mkdir "$dir/$service"
    mv "$dir/$artifact" "$dir/$service/"
    docker build --network none --file "$config/$service.Dockerfile" --tag "mai-portfolio-$service:${tag#test4c4g-}" "$dir/$service"
  done
}

backup_databases() {
  local destination=$1 db name sessions
  # The two fixed test databases must have no external writers during a deployment.
  for db in portfolio_test mai_portfolio_ai_test; do
    sessions=$(runuser -u postgres -- psql -X -At -v ON_ERROR_STOP=1 -d postgres \
      -c "SELECT count(*) FROM pg_stat_activity WHERE datname = '$db' AND backend_type = 'client backend'") || return 1
    [[ "$sessions" == 0 ]] || return 1
  done
  for name in spring ai; do
    case "$name" in
      spring) db=portfolio_test ;;
      ai) db=mai_portfolio_ai_test ;;
    esac
    runuser -u postgres -- pg_dump --format=custom --dbname="$db" > "$destination/$name.dump" || return 1
    pg_restore --list "$destination/$name.dump" >/dev/null || return 1
  done
}

write_current() {
  local tag=$1 previous=$2
  jq -n --arg release "$tag" --arg previous "$previous" --arg timestamp "$(date -u +%FT%TZ)" \
    '{release:$release, previous:$previous, activatedAt:$timestamp}' > "$state/current.new" &&
    mv -T "$state/current.new" "$state/current.json"
}

current_tag() {
  if [[ -f "$state/current.json" ]]; then
    jq -er '.release' "$state/current.json"
  fi
}

deploy() {
  local tag=$1 previous stage backup
  [[ ! -e "$state/pending.json" ]] || die "A previous cutover needs operator recovery."
  previous=$(current_tag)
  [[ -z "$previous" ]] || valid_tag "$previous" || die "Invalid current release record."
  if [[ "$tag" == "$previous" ]]; then
    wait_stack "$tag"
    return
  fi
  stage=$(mktemp -d "$state/staging/$tag.XXXXXXXX")
  trap 'rm -rf -- '"$(printf '%q' "$stage")" EXIT
  stage_release "$tag" "$stage"
  build_images "$stage" "$tag"

  backup=$(mktemp -d "$state/backups/$tag.XXXXXXXX")
  jq -n --arg target "$tag" --arg previous "$previous" --arg backup "$backup" \
    '{target:$target, previous:$previous, backup:$backup}' > "$state/pending.new"
  mv -T "$state/pending.new" "$state/pending.json"
  if ! stop_stack "${previous:-$tag}"; then
    die "Could not confirm all writers stopped. No database backup or migration was attempted."
  fi
  if ! backup_databases "$backup"; then
    if [[ -z "$previous" ]] || { compose "$previous" up -d && wait_stack "$previous"; }; then
      rm -- "$state/pending.json"
    fi
    die "Backups failed. No migrations were attempted."
  fi

  # Both the Alembic container and the Spring Flyway startup can write schema changes.
  # Any failure from this point requires an operator to confirm database compatibility.
  if ! compose "$tag" run --rm --no-deps ai python -m alembic upgrade head > "$backup/alembic.log" 2>&1 ||
      ! compose "$tag" up -d || ! wait_stack "$tag"; then
    if ! stop_stack "$tag"; then
      printf '[deploy] Could not confirm candidate stopped. Inspect it before recovery.\n' >&2
    fi
    die "Cutover failed. See pending.json and $backup; database recovery belongs to mai-ops."
  fi
  write_current "$tag" "$previous"
  rm -- "$state/pending.json"
  printf '[deploy] Active release %s\n' "$tag"
}

case "${1:-}" in
  deploy)
    [[ $# == 2 ]] && valid_tag "$2" || die "Usage: mai-portfolio-deploy deploy test4c4g-<sha12>"
    deploy "$2"
    ;;
  status)
    [[ $# == 1 ]] || die "Usage: mai-portfolio-deploy status"
    for record in current pending; do
      if [[ -f "$state/$record.json" ]]; then
        cat "$state/$record.json"
      fi
    done
    docker ps --filter label=com.docker.compose.project=mai-portfolio --format '{{.Names}} {{.Image}} {{.Status}}'
    ;;
  *) die "Usage: mai-portfolio-deploy deploy <tag> | status" ;;
esac
