#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || ! "$1" =~ ^test4c4g-[0-9a-f]{12}$ ]]; then
  printf 'Usage: mai-portfolio-pull test4c4g-<sha12>\n' >&2
  exit 2
fi

exec sudo -n /usr/local/sbin/mai-portfolio-deploy deploy "$1"
