#!/bin/sh
# Small, pure helper shared by session_supervisor.sh and its host-side test.
# Arguments are integer monotonic uptime seconds: last daemon heartbeat, current uptime,
# and the stale threshold. Invalid/future stamps are deliberately NOT considered stale.
ocbmd_heartbeat_is_stale() {
  _ocbmd_stamp=${1:-}
  _ocbmd_now=${2:-}
  _ocbmd_threshold=${3:-60}

  case "$_ocbmd_stamp:$_ocbmd_now:$_ocbmd_threshold" in
    *[!0-9:]*|:*|*::*|*:) return 1 ;;
  esac
  [ "$_ocbmd_now" -ge "$_ocbmd_stamp" ] || return 1
  [ $((_ocbmd_now - _ocbmd_stamp)) -ge "$_ocbmd_threshold" ]
}
