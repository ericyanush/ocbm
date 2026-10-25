#!/usr/bin/env bash
# OCBM stock-USB root-shell bootstrap for CPC200-CCPA firmware 2026.07.31.1733.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BIN="$REPO/target/release/ocbm-stock-shell"
BOX_HOST="${CCPA_HOST:-192.168.50.2}"
EXPECTED_VERSION="${CCPA_EXPECTED_FIRMWARE:-2026.07.31.1733}"
EXPECTED_HARDWARE="${CCPA_EXPECTED_HARDWARE:-YMA0-WN16-0003}"

usage() {
  cat <<'EOF'
Usage: tools/ccpa_stock_usb_bootstrap.sh build|scan|bootstrap|shell|backup [--run-dir DIR]

  build      Build the OCBM stock-USB sender.
  scan       List attached stock CPC200-CCPA adapters (read-only).
  bootstrap  Upload the OCBM shell boot hook, then replug the adapter.
  shell      Verify root and firmware version, then open the shell.
  backup     Use ncm_base_install.sh to save and verify this unit's full NOR.
             --run-dir shares backup artifacts with the later NCM conversion run.

The bootstrap writes exactly /script/ocbm_stock_shell.sh and /etc/profile.
Both files persist across reboot. The bootstrap does not preserve the original
/etc/profile. NCM state and shell logs use /tmp.
After replug, the adapter uses 192.168.50.2:23 and leases .100-.150 to hosts.
After backup, review the 2026 stock boot scripts before running install or strip.
EOF
}

need_bin() {
  [ -x "$BIN" ] || { echo "Missing $BIN. Run '$0 build'." >&2; exit 1; }
}

case "${1:-}" in
  build)
    (cd "$REPO" && cargo build --locked --release -p ocbm-host --bin ocbm-stock-shell)
    ;;
  scan)
    need_bin
    "$BIN" scan
    ;;
  bootstrap)
    need_bin
    "$BIN" scan
    cat <<'EOF'
This replaces /etc/profile and adds /script/ocbm_stock_shell.sh on the adapter.
Both files are written to persistent flash. The original stock profile cannot
be recovered by this USB protocol alone. Keep the adapter attached to this host.
EOF
    printf 'Confirm hardware and firmware (%s/%s): ' "$EXPECTED_HARDWARE" "$EXPECTED_VERSION"
    read -r answer
    [ "$answer" = "$EXPECTED_HARDWARE/$EXPECTED_VERSION" ] || { echo 'Stopped.'; exit 1; }
    "$BIN" bootstrap
    echo "Unplug and replug the adapter; wait about 30 seconds, then run '$0 shell'."
    ;;
  shell)
    identity="$(python3 "$REPO/tools/boxsh.py" --host "$BOX_HOST" --timeout 10 run 'id; cat /etc/software_version')"
    printf '%s\n' "$identity"
    [[ "$identity" == *'uid=0(root)'* ]] || { echo 'Root shell was not verified.' >&2; exit 1; }
    [[ "$identity" == *"$EXPECTED_VERSION"* ]] || {
      echo "Unexpected firmware version; expected $EXPECTED_VERSION." >&2
      exit 1
    }
    echo "Root verified. Type 'exit' to close the interactive shell."
    nc "$BOX_HOST" 23
    ;;
  backup)
    shift
    run_dir=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --run-dir) [ $# -ge 2 ] || { echo '--run-dir needs a value' >&2; exit 2; }; run_dir="$2"; shift 2 ;;
        -*) echo "unknown backup option: $1" >&2; exit 2 ;;
        *) echo "unexpected backup argument: $1" >&2; exit 2 ;;
      esac
    done
    identity="$(python3 "$REPO/tools/boxsh.py" --host "$BOX_HOST" --timeout 10 run 'id; cat /etc/software_version')"
    [[ "$identity" == *'uid=0(root)'* && "$identity" == *"$EXPECTED_VERSION"* ]] || {
      echo "Root/version preflight failed: $identity" >&2
      exit 1
    }
    [ -n "$run_dir" ] || run_dir="$REPO/scratchpad/stock_usb_bootstrap_$(date +%Y%m%d_%H%M%S)"
    case "$run_dir" in /*) ;; *) run_dir="$REPO/$run_dir" ;; esac
    "$REPO/tools/ncm_base_install.sh" --host "$BOX_HOST" --via telnet \
      --run-dir "$run_dir" backup
    echo "Backup: $run_dir/backup_pre/full_nor_16MB.bin"
    echo "This snapshot includes the stock-USB bootstrap files; it is not a pristine stock dump."
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
