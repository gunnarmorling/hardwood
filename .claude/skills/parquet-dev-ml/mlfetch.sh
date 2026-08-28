#!/usr/bin/env bash
# Download parquet-dev monthly mbox archives with caching.
#
# Usage: mlfetch.sh YYYY-MM [YYYY-MM ...]
# Prints the local path of each requested month's mbox on its own line.
#
# Caching: past months are immutable and fetched once; the current month is
# live and always re-downloaded. Cache dir overridable via $PARQUET_ML_CACHE.
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "usage: mlfetch.sh YYYY-MM [YYYY-MM ...]" >&2
  exit 2
fi

CACHE="${PARQUET_ML_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/parquet-dev-ml}"
mkdir -p "$CACHE"
CUR="$(date +%Y-%m)"

for d in "$@"; do
  if [[ ! "$d" =~ ^[0-9]{4}-[0-9]{2}$ ]]; then
    echo "skipping malformed month: $d (expected YYYY-MM)" >&2
    continue
  fi
  out="$CACHE/parquet-dev-$d.mbox"
  if [[ "$d" == "$CUR" || ! -s "$out" ]]; then
    url="https://lists.apache.org/api/mbox.lua?list=dev&domain=parquet.apache.org&d=$d"
    curl -fsSL "$url" -o "$out"
  fi
  echo "$out"
done
