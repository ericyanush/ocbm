#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
. "$SCRIPT_DIR/ocbmd_liveness.sh"

assert_stale() {
  if ! ocbmd_heartbeat_is_stale "$1" "$2" "${3:-60}"; then
    echo "FAIL: expected stale heartbeat stamp=$1 now=$2 threshold=${3:-60}" >&2
    exit 1
  fi
}

assert_fresh() {
  if ocbmd_heartbeat_is_stale "$1" "$2" "${3:-60}"; then
    echo "FAIL: expected fresh/invalid heartbeat stamp=$1 now=$2 threshold=${3:-60}" >&2
    exit 1
  fi
}

# Forward wall-clock corrections are irrelevant: both values are monotonic uptime.
assert_fresh 100 100
assert_fresh 100 159
assert_stale 100 160
assert_stale 100 200 30
assert_fresh 100 99        # future stamp (inconsistent/corrupt data)
assert_fresh '' 200        # startup/missing content
assert_fresh invalid 200   # partial write or legacy mtime-only marker
assert_fresh 100 200 bad   # invalid threshold

echo "ocbmd liveness tests passed"
